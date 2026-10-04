import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import {
  getSessionInfo,
  getSessionMessages,
  query,
  type CanUseTool,
  type PermissionMode,
  type PermissionResult,
  type Query,
  type SDKMessage,
  type SDKUserMessage,
} from '@anthropic-ai/claude-agent-sdk';
import { SessionStore } from './sessions.js';
import { createWorktree, projects, pullRequests, recentSessions } from './workspace.js';

export const ROOT = path.resolve(fileURLToPath(import.meta.url), '../../..');
const store = new SessionStore(path.join(ROOT, 'sessions.json'));

// Presence check only; the value is never read.
const apiKeyPresent = 'ANTHROPIC_API_KEY' in process.env;
if (apiKeyPresent) {
  console.warn(
    '\x1b[33;1m! ANTHROPIC_API_KEY is set: Claude Code will bill the API, not your subscription.\n' +
      '  Unset it and restart to use your Claude login.\x1b[0m',
  );
}

export type ClientMsg =
  | { type: 'prompt'; text: string }
  | { type: 'interrupt' }
  | { type: 'set_cwd'; cwd: string }
  | { type: 'new_session'; cwd?: string; worktree?: boolean }
  | { type: 'resume'; sessionId: string; cwd?: string }
  | { type: 'set_mode'; mode: PermissionMode }
  | { type: 'set_model'; model: string }
  | { type: 'home'; limit?: number }
  | { type: 'prs' }
  | { type: 'models' }
  | { type: 'ls'; path?: string }
  | ({ type: 'permission_response'; id: string } & PermissionReply);

type PermissionReply = { allow: boolean; answers?: Record<string, string>; mode?: PermissionMode };

// bypassPermissions is deliberately not offered.
const MODES = new Set<PermissionMode>(['default', 'acceptEdits', 'plan']);

/** Where a Conn's events go and its commands come from: a browser WebSocket or the Minecraft bridge. */
export interface Transport {
  send(msg: Record<string, unknown>): void;
  onMessage(handler: (msg: ClientMsg) => void): void;
  onClose(handler: () => void): void;
}

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

// Fallback until a live query reports its own list; then that list is reused.
let knownModels: { value: string; displayName: string; description: string }[] = [
  { value: 'default', displayName: 'Default', description: 'Your Claude Code default' },
  { value: 'opus', displayName: 'Opus', description: 'Most capable for complex work' },
  { value: 'sonnet', displayName: 'Sonnet', description: 'Fast and capable for everyday work' },
  { value: 'haiku', displayName: 'Haiku', description: 'Fastest for quick tasks' },
];

/** One client (a browser tab or a Minecraft world): owns at most one live query. */
export class Conn {
  private q: Query | null = null;
  private input: InputQueue | null = null;
  private running = false;
  private cwd = store.recentDirs()[0] ?? process.cwd();
  private sessionId: string | null = null;
  private title = '';
  private mode: PermissionMode = 'default';
  private model: string | undefined;
  private pending = new Map<string, (reply: PermissionReply) => void>();

  constructor(
    private transport: Transport,
    private opts: { systemPromptAppend?: string } = {},
  ) {
    transport.onMessage((msg) => this.onClientMessage(msg));
    transport.onClose(() => this.stop());
    this.send({ type: 'hello', home: os.homedir(), apiKeyPresent });
    this.sendCwd();
    this.send({ type: 'mode', mode: this.mode });
  }

  send(msg: Record<string, unknown>) {
    this.transport.send(msg);
  }

  stop() {
    for (const reply of [...this.pending.values()]) reply({ allow: false });
    this.input?.close();
    this.q?.close();
    this.q = null;
    this.input = null;
    this.setRunning(false);
  }

  private onClientMessage(msg: ClientMsg) {
    switch (msg.type) {
      case 'prompt':
        return this.prompt(String(msg.text ?? ''));
      case 'interrupt':
        this.q?.interrupt().catch((err) => this.sendError(err));
        return;
      case 'set_cwd':
        return void this.setCwd(String(msg.cwd ?? ''));
      case 'new_session':
        return void this.newSession(msg.cwd, Boolean(msg.worktree));
      case 'resume':
        return void this.resume(String(msg.sessionId ?? '').trim(), msg.cwd);
      case 'set_model':
        return void this.setModel(String(msg.model ?? ''));
      case 'home':
        return void this.sendHome(Math.min(Number(msg.limit) || 12, 60));
      case 'prs':
        return void this.sendPrs();
      case 'models':
        return void this.sendModels();
      case 'ls':
        return void this.listDir(String(msg.path ?? ''));
      case 'set_mode':
        return void this.setMode(msg.mode);
      case 'permission_response':
        this.pending.get(String(msg.id))?.(msg);
        return;
    }
  }

  private busy() {
    if (this.running) this.sendError('Stop the current run first.');
    return this.running;
  }

  private async setCwd(input: string) {
    if (this.busy()) return;
    if (await this.switchDir(input)) this.sendCwd();
  }

  /** Points this connection at another directory (no session yet). Returns false if it isn't one. */
  private async switchDir(input: string) {
    const dir = path.resolve(input.trim().replace(/^~(?=$|\/)/, os.homedir()));
    const stat = await fs.stat(dir).catch(() => null);
    if (!stat?.isDirectory()) {
      this.sendError(`Not a directory: ${dir}`);
      return false;
    }
    this.stop();
    this.cwd = dir;
    this.sessionId = null;
    store.touchDir(dir);
    return true;
  }

  private async sendHome(limit: number) {
    try {
      const [sessions, projectList] = await Promise.all([recentSessions(limit), projects(store.recentDirs())]);
      this.send({ type: 'home', sessions, projects: projectList });
    } catch (err) {
      this.sendError(err);
    }
  }

  private async sendPrs() {
    try {
      this.send({ type: 'prs', prs: await pullRequests() });
    } catch (err) {
      this.send({ type: 'prs', prs: [], error: `Couldn't load pull requests (${err instanceof Error ? err.message.split('\n')[0] : err}). Is gh installed and logged in?` });
    }
  }

  private async sendModels() {
    const models = (await this.q?.supportedModels().catch(() => null)) ?? knownModels;
    knownModels = models;
    this.send({ type: 'models', models, current: this.model ?? 'default' });
  }

  /** Lists a folder inside the project, for attaching files as @path mentions. */
  private async listDir(rel: string) {
    const dir = path.resolve(this.cwd, rel);
    if (dir !== this.cwd && !dir.startsWith(this.cwd + path.sep)) return this.sendError('That folder is outside the project.');
    try {
      const entries = (await fs.readdir(dir, { withFileTypes: true }))
        .filter((e) => !e.name.startsWith('.') && e.name !== 'node_modules')
        .map((e) => ({ name: e.name, dir: e.isDirectory() }))
        .sort((a, b) => Number(b.dir) - Number(a.dir) || a.name.localeCompare(b.name))
        .slice(0, 300);
      this.send({ type: 'ls', path: path.relative(this.cwd, dir), entries });
    } catch (err) {
      this.sendError(err);
    }
  }

  private async setModel(model: string) {
    this.model = model && model !== 'default' ? model : undefined;
    this.send({ type: 'model', model: this.model ?? 'default' });
    await this.q?.setModel(this.model).catch((err) => this.sendError(err));
  }

  private sendCwd() {
    this.send({
      type: 'cwd',
      cwd: this.cwd,
      recentDirs: store.recentDirs(),
      sessions: store.list(this.cwd),
      sessionId: this.sessionId,
    });
  }

  private async setMode(mode: PermissionMode) {
    if (!MODES.has(mode)) return;
    this.mode = mode;
    this.send({ type: 'mode', mode });
    await this.q?.setPermissionMode(mode).catch((err) => this.sendError(err));
  }

  /** Forwards a tool-permission prompt to the browser and waits for Allow / Deny. */
  private askPermission(
    toolName: string,
    input: Record<string, unknown>,
    opts: Parameters<CanUseTool>[2],
  ): Promise<PermissionResult> {
    const deny = (message: string): PermissionResult => ({ behavior: 'deny', message });
    if (opts.signal.aborted) return Promise.resolve(deny('Cancelled.'));
    const id = crypto.randomUUID();
    return new Promise((resolve) => {
      const finish = (result: PermissionResult) => {
        this.pending.delete(id);
        opts.signal.removeEventListener('abort', onAbort);
        resolve(result);
      };
      const onAbort = () => {
        this.send({ type: 'permission_cancel', id });
        finish(deny('Cancelled.'));
      };
      opts.signal.addEventListener('abort', onAbort, { once: true });

      this.pending.set(id, (reply) => {
        if (!reply.allow) return finish(deny('The user denied this request.'));
        // AskUserQuestion takes the user's choices as `answers` on its input.
        const updatedInput = reply.answers ? { ...input, answers: reply.answers } : input;
        // Approving a plan leaves plan mode for the mode the user picked.
        const next = toolName === 'ExitPlanMode' && reply.mode && MODES.has(reply.mode) ? reply.mode : null;
        if (!next) return finish({ behavior: 'allow', updatedInput });
        this.mode = next;
        this.send({ type: 'mode', mode: next });
        finish({ behavior: 'allow', updatedInput, updatedPermissions: [{ type: 'setMode', mode: next, destination: 'session' }] });
      });

      this.send({
        type: 'permission_request',
        id,
        toolName,
        input,
        title: opts.title,
        decisionReason: opts.decisionReason,
        blockedPath: opts.blockedPath,
        toolUseID: opts.toolUseID,
      });
    });
  }

  private async newSession(cwd?: string, worktree = false) {
    if (this.busy()) return;
    if (cwd && !(await this.switchDir(cwd))) return;
    if (worktree) {
      try {
        if (!(await this.switchDir(await createWorktree(this.cwd)))) return;
      } catch (err) {
        return this.sendError(err);
      }
    }
    this.stop();
    this.sessionId = null;
    if (cwd || worktree) this.sendCwd();
    this.send({ type: 'session', sessionId: null, sessions: store.list(this.cwd) });
  }

  private async resume(id: string, cwd?: string) {
    if (this.busy()) return;
    if (cwd && cwd !== this.cwd) {
      if (!(await this.switchDir(cwd))) return;
      this.sendCwd();
    }
    if (!/^[\w-]{8,64}$/.test(id)) return this.sendError('Invalid session ID.');
    const messages = await getSessionMessages(id, { dir: this.cwd }).catch(() => []);
    if (!messages.length) return this.sendError(`No session ${id} found for ${this.cwd}`);
    this.stop();
    this.sessionId = id;
    if (!store.has(this.cwd, id)) {
      const info = await getSessionInfo(id, { dir: this.cwd }).catch(() => undefined);
      store.upsert(this.cwd, id, { title: info?.customTitle ?? info?.summary ?? id });
    }
    this.send({ type: 'session', sessionId: id, sessions: store.list(this.cwd) });
    this.send({ type: 'history', sessionId: id, messages });
  }

  private prompt(text: string) {
    if (!text.trim() || this.running) return;
    if (!this.sessionId) this.title = text;
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
        resume: this.sessionId ?? undefined,
        systemPrompt: { type: 'preset', preset: 'claude_code', append: this.opts.systemPromptAppend },
        includePartialMessages: true,
        permissionMode: this.mode,
        model: this.model,
        canUseTool: (toolName, input, opts) => this.askPermission(toolName, input, opts),
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
    const sid = 'session_id' in m && typeof m.session_id === 'string' ? m.session_id : null;
    if (sid && sid !== this.sessionId) {
      store.upsert(this.cwd, sid, { title: this.title, replaces: this.sessionId });
      this.sessionId = sid;
      this.send({ type: 'session', sessionId: sid, sessions: store.list(this.cwd) });
    }
    if (m.type === 'system' && (m.subtype === 'init' || m.subtype === 'status')) {
      if (m.permissionMode && m.permissionMode !== this.mode) {
        this.mode = m.permissionMode;
        this.send({ type: 'mode', mode: m.permissionMode });
      }
    }
    if (m.type === 'result') {
      this.setRunning(false);
      if (this.sessionId) store.touch(this.cwd, this.sessionId);
    }
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
