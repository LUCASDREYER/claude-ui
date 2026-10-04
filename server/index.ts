import http from 'node:http';
import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { execFile } from 'node:child_process';
import { WebSocketServer, WebSocket } from 'ws';
import { Conn, ROOT, type ClientMsg } from './conn.js';

const HOST = '127.0.0.1';
const PORT = Number(process.env.PORT) || 3456;
const HOSTS = new Set([`127.0.0.1:${PORT}`, `localhost:${PORT}`]);
const ORIGINS = new Set([...HOSTS].map((h) => `http://${h}`));

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
  const conn = new Conn({
    send: (msg) => ws.readyState === WebSocket.OPEN && ws.send(JSON.stringify(msg)),
    onMessage: (handler) =>
      ws.on('message', (data) => {
        let msg: ClientMsg;
        try {
          msg = JSON.parse(data.toString());
        } catch {
          return;
        }
        handler(msg);
      }),
    onClose: (handler) => ws.on('close', handler),
  });
  conns.add(conn);
  ws.on('close', () => conns.delete(conn));
});

server.on('error', (err: NodeJS.ErrnoException) => {
  console.error(err.code === 'EADDRINUSE' ? `Port ${PORT} is in use. Set PORT=... to pick another.` : err);
  process.exit(1);
});

/** Opens the UI as a chromeless app window (no tabs or address bar) when a Chromium browser is installed. */
function openWindow(url: string) {
  if (process.platform === 'darwin') {
    const dirs = ['/Applications', path.join(os.homedir(), 'Applications')];
    const app = ['Google Chrome', 'Microsoft Edge', 'Brave Browser', 'Chromium']
      .find((name) => dirs.some((d) => existsSync(path.join(d, `${name}.app`))));
    if (app) return execFile('open', ['-na', app, '--args', `--app=${url}`], () => {});
    return execFile('open', [url], () => {});
  }
  if (process.platform === 'linux') {
    execFile('google-chrome', [`--app=${url}`], (err) => err && execFile('xdg-open', [url], () => {}));
  }
}

server.listen(PORT, HOST, () => {
  const url = `http://${HOST}:${PORT}`;
  console.log(`claude-ui on ${url}`);
  if (!process.env.NO_OPEN) openWindow(url);
});

function shutdown() {
  for (const c of conns) c.stop();
  process.exit(0);
}
for (const sig of ['SIGINT', 'SIGTERM'] as const) process.on(sig, shutdown);
// Launched by the macOS app: exit when the app goes away and closes our stdin.
if (process.env.CLAUDE_UI_APP) process.stdin.on('end', shutdown).resume();
