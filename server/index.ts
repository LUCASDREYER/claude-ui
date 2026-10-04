import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';
import { query, type Query, type SDKMessage, type SDKUserMessage } from '@anthropic-ai/claude-agent-sdk';

const ROOT = path.resolve(fileURLToPath(import.meta.url), '../../..');
const HOST = '127.0.0.1';
const PORT = Number(process.env.PORT) || 3456;
const HOSTS = new Set([`127.0.0.1:${PORT}`, `localhost:${PORT}`]);
const ORIGINS = new Set([...HOSTS].map((h) => `http://${h}`));

// Presence check only; the value is never read.
const apiKeyPresent = 'ANTHROPIC_API_KEY' in process.env;
if (apiKeyPresent) {
  console.warn(
    '\x1b[33;1m! ANTHROPIC_API_KEY is set: Claude Code will bill the API, not your subscription.\n' +
      '  Unset it and restart to use your Claude login.\x1b[0m',
  );
}

type ClientMsg = { type: 'prompt'; text: string } | { type: 'interrupt' };

/** User turns for a streaming-input query. Stays open between turns so the CLI process is reused. */
class InputQueue implements AsyncIterable<SDKUserMessage> {
  private items: SDKUserMessage[] = [];
  private wake: (() => void) | null = null;
  private closed = false;

  push(text: string) {
    this.items.push({ type: 'user', message: { role: 'user', content: text }, parent_tool_use_id: null });
    this.wake?.();
  }

  close() {
    this.closed = true;
    this.wake?.();
  }

  async *[Symbol.asyncIterator]() {
    while (true) {
      const next = this.items.shift();
      if (next) {
        yield next;
        continue;
      }
      if (this.closed) return;
      await new Promise<void>((resolve) => (this.wake = resolve));
      this.wake = null;
    }
  }
}

/** One browser tab: owns at most one live query. */
class Conn {
  private q: Query | null = null;
  private input: InputQueue | null = null;
  private running = false;
  private cwd = process.cwd();

  constructor(private ws: WebSocket) {
    ws.on('message', (data) => this.onClientMessage(data.toString()));
    ws.on('close', () => this.stop());
    this.send({ type: 'hello', cwd: this.cwd, apiKeyPresent });
  }

  send(msg: unknown) {
    if (this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  }

  stop() {
    this.input?.close();
    this.q?.close();
    this.q = null;
    this.input = null;
    this.setRunning(false);
  }

  private onClientMessage(raw: string) {
    let msg: ClientMsg;
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }
    switch (msg.type) {
      case 'prompt':
        return this.prompt(String(msg.text ?? ''));
      case 'interrupt':
        this.q?.interrupt().catch((err) => this.sendError(err));
        return;
    }
  }

  private prompt(text: string) {
    if (!text.trim() || this.running) return;
    if (!this.q) this.start();
    this.input!.push(text);
    this.setRunning(true);
  }

  private start() {
    const input = new InputQueue();
    const q = query({
      prompt: input,
      options: {
        cwd: this.cwd,
        systemPrompt: { type: 'preset', preset: 'claude_code' },
        includePartialMessages: true,
        // No approval UI yet: anything that would prompt is denied.
        permissionPrompts: 'none',
        stderr: (data) => process.stderr.write(data),
      },
    });
    this.q = q;
    this.input = input;
    void this.pump(q);
  }

  private async pump(q: Query) {
    try {
      for await (const m of q) this.onSdkMessage(m);
    } catch (err) {
      if (this.q === q) this.sendError(err);
    } finally {
      if (this.q === q) {
        this.q = null;
        this.input = null;
        this.setRunning(false);
      }
    }
  }

  private onSdkMessage(m: SDKMessage) {
    if (m.type === 'result') this.setRunning(false);
    this.send({ type: 'sdk', msg: m });
  }

  private setRunning(running: boolean) {
    if (this.running === running) return;
    this.running = running;
    this.send({ type: 'running', running });
  }

  private sendError(err: unknown) {
    this.send({ type: 'error', message: err instanceof Error ? err.message : String(err) });
  }
}

function csp(nonce: string) {
  return [
    "default-src 'none'",
    `script-src 'nonce-${nonce}' https://cdnjs.cloudflare.com`,
    "style-src 'unsafe-inline'",
    'img-src data:',
    `connect-src ${[...HOSTS].map((h) => `ws://${h}`).join(' ')}`,
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
  ].join('; ');
}

const server = http.createServer(async (req, res) => {
  // Host check blocks DNS-rebinding attacks against this local server.
  if (!HOSTS.has(req.headers.host ?? '')) return void res.writeHead(403).end();
  const { pathname } = new URL(req.url ?? '/', 'http://local');
  if (req.method !== 'GET' || (pathname !== '/' && pathname !== '/index.html')) {
    return void res.writeHead(404).end();
  }
  const nonce = crypto.randomBytes(16).toString('base64');
  const html = (await fs.readFile(path.join(ROOT, 'public/index.html'), 'utf8')).replaceAll('__NONCE__', nonce);
  res.writeHead(200, {
    'content-type': 'text/html; charset=utf-8',
    'content-security-policy': csp(nonce),
    'cache-control': 'no-store',
    'referrer-policy': 'no-referrer',
    'x-content-type-options': 'nosniff',
  });
  res.end(html);
});

const conns = new Set<Conn>();
const wss = new WebSocketServer({
  server,
  path: '/ws',
  maxPayload: 8 * 1024 * 1024,
  // Browsers don't apply CORS to WebSockets, so any open tab could connect without this check.
  verifyClient: ({ origin, req }: { origin: string; req: http.IncomingMessage }) => ORIGINS.has(origin) && HOSTS.has(req.headers.host ?? ''),
});
wss.on('connection', (ws) => {
  const conn = new Conn(ws);
  conns.add(conn);
  ws.on('close', () => conns.delete(conn));
});

server.on('error', (err: NodeJS.ErrnoException) => {
  console.error(err.code === 'EADDRINUSE' ? `Port ${PORT} is in use. Set PORT=... to pick another.` : err);
  process.exit(1);
});

server.listen(PORT, HOST, () => {
  const url = `http://${HOST}:${PORT}`;
  console.log(`claude-ui on ${url}`);
  if (!process.env.NO_OPEN) {
    const opener = process.platform === 'darwin' ? 'open' : process.platform === 'linux' ? 'xdg-open' : null;
    if (opener) execFile(opener, [url], () => {});
  }
});

for (const sig of ['SIGINT', 'SIGTERM'] as const) {
  process.on(sig, () => {
    for (const c of conns) c.stop();
    process.exit(0);
  });
}
