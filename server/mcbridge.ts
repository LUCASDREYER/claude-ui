/** Minecraft side of the bridge: turns chat into Conn messages and Conn events into tellraw commands. */
import os from 'node:os';
import { Conn, type ClientMsg } from './conn.js';
import type { SessionEntry } from './sessions.js';

const ORANGE = '#D97757';
const NPC = '@e[type=minecraft:mannequin,tag=claude,limit=1]';

const SYSTEM_APPEND =
  'The user is talking to you through Minecraft chat. Keep replies short: a few plain sentences, ' +
  'no tables, headings or long code blocks. Put code in files and say what you changed.';

// ---------- chat formatting

type Part = { text: string; color?: string; bold?: boolean; italic?: boolean; hover?: string; command?: string; copy?: string };
type Line = { text: string; code?: boolean };

// Keeps newlines; drops other control characters and § (legacy formatting codes).
const clean = (s: string) => s.replace(/[\u0000-\u0009\u000b-\u001f\u007f§]/g, '');
const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, n - 1)}…` : s);

/** Markdown to short chat lines: inline markup stripped, long code blocks summarized. */
function chatLines(md: string, max = 16): Line[] {
  const out: Line[] = [];
  let fence: string[] | null = null;
  for (const raw of md.split('\n')) {
    if (/^\s*```/.test(raw)) {
      if (!fence) fence = [];
      else {
        if (fence.length <= 6) out.push(...fence.map((text) => ({ text: `  ${text}`, code: true })));
        else out.push({ text: `[${fence.length}-line code block — see the desktop app]`, code: true });
        fence = null;
      }
      continue;
    }
    if (fence) {
      fence.push(raw);
      continue;
    }
    if (!raw.trim() || /^\s*\|?\s*:?-{3,}/.test(raw)) continue;
    const text = raw
      .replace(/^#+\s*/, '')
      .replace(/^\s*[-*]\s+/, '• ')
      .replace(/^\s*\|(.*)\|\s*$/, (_, row: string) => row.split('|').map((c) => c.trim()).join(' · '))
      .replace(/\*\*(.+?)\*\*/g, '$1')
      .replace(/`([^`]+)`/g, '$1')
      .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1');
    out.push({ text });
  }
  if (out.length <= max) return out;
  return [...out.slice(0, max), { text: `… ${out.length - max} more lines in the desktop app` }];
}

function describe(input: Record<string, unknown>, cwd: string) {
  const v = input.command ?? input.file_path ?? input.notebook_path ?? input.pattern ?? input.url ?? input.query ?? input.description ?? input.skill ?? input.prompt ?? '';
  const first = String(v).split('\n')[0] ?? '';
  return clip(first.startsWith(cwd + '/') ? first.slice(cwd.length + 1) : first, 90);
}

function inputHover(name: string, input: Record<string, unknown>) {
  if (name === 'Edit' && typeof input.old_string === 'string') {
    return clip(`${input.file_path}\n- ${input.old_string}\n+ ${input.new_string}`, 700);
  }
  return clip(JSON.stringify(input, null, 2), 700);
}

function resultText(content: unknown): string {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map((b) => (b?.type === 'text' ? b.text : `[${b?.type}]`)).join('\n');
  return JSON.stringify(content);
}

// ---------- bridge

type Pending = { id: string; toolName: string; codes: number[]; questions?: { question: string; options: { label: string }[] }[]; answers?: Record<string, string> };

/** A Conn transport that speaks Minecraft: chat in, tellraw out. */
export class Bridge {
  private conn: Conn;
  private deliver: (msg: ClientMsg) => void = () => {};
  private buttons = new Map<number, () => void>();
  private nextCode = 1;
  private pending: Pending[] = [];
  private cwd = '';
  private sessionId: string | null = null;
  private sessions: SessionEntry[] = [];
  private mode = 'default';
  private running = false;
  private model = '';
  private cost: number | null = null;
  private apiKeyPresent = false;
  private announceCwd = false;

  constructor(private command: (cmd: string) => void) {
    this.conn = new Conn(
      { send: (m) => this.onServer(m), onMessage: (h) => (this.deliver = h), onClose: () => {} },
      { systemPromptAppend: SYSTEM_APPEND },
    );
  }

  stop() {
    this.conn.stop();
  }

  // ----- output

  private tell(...parts: Part[]) {
    const comps = parts.map(({ hover, command, copy, text, ...style }) => ({
      text: clean(text),
      ...style,
      ...(hover && { hover_event: { action: 'show_text', value: clean(hover) } }),
      ...(command && { click_event: { action: 'run_command', command } }),
      ...(copy && { click_event: { action: 'copy_to_clipboard', value: copy } }),
    }));
    this.command(`tellraw @a ${JSON.stringify(comps)}`);
  }

  private info(text: string, color = 'gray') {
    this.tell({ text, color });
  }

  private say(md: string) {
    chatLines(md).forEach((line, i) => {
      const body: Part = { text: line.text, color: line.code ? 'aqua' : undefined };
      if (i === 0) this.tell({ text: '<' }, { text: 'Claude', color: ORANGE }, { text: '> ' }, body);
      else this.tell(body);
    });
  }

  /** A clickable chat button; clicking runs /trigger, which the console log reports back to us. */
  private button(label: string, color: string, hover: string, action: () => void, owner?: Pending): Part {
    const code = this.nextCode++;
    this.buttons.set(code, action);
    owner?.codes.push(code);
    return { text: `[${label}]`, color, bold: true, hover, command: `/trigger claude set ${code}` };
  }

  private npcStatus(text: string, color: string) {
    this.command(`data merge entity ${NPC} {description:${JSON.stringify({ text, color })}}`);
  }

  // ----- Conn → Minecraft

  private onServer(m: Record<string, unknown>) {
    switch (m.type) {
      case 'hello':
        this.apiKeyPresent = Boolean(m.apiKeyPresent);
        return;
      case 'cwd':
        this.cwd = String(m.cwd);
        this.sessions = m.sessions as SessionEntry[];
        this.sessionId = (m.sessionId as string | null) ?? null;
        if (this.announceCwd) this.info(`Working in ${this.cwd}. New session.`);
        this.announceCwd = false;
        return;
      case 'error':
        this.announceCwd = false;
        return this.info(String(m.message), 'red');
      case 'session':
        this.sessionId = (m.sessionId as string | null) ?? null;
        this.sessions = m.sessions as SessionEntry[];
        return;
      case 'mode':
        this.mode = String(m.mode);
        return;
      case 'running':
        this.running = Boolean(m.running);
        if (this.running) this.npcStatus('working…', 'yellow');
        else this.npcStatus('Claude Code', 'gray');
        if (!this.running) this.clearPending();
        return;
      case 'history': {
        const title = this.sessions.find((s) => s.id === m.sessionId)?.title ?? String(m.sessionId).slice(0, 8);
        return this.info(`Resumed "${title}" (${(m.messages as unknown[]).length} messages). Carry on.`);
      }
      case 'permission_request':
        return this.permission(m as Pending & { input: Record<string, unknown>; title?: string });
      case 'permission_cancel':
        return this.settle(String(m.id), 'request cancelled');
      case 'sdk':
        return this.onSdk(m.msg as Record<string, any>);
    }
  }

  private onSdk(m: Record<string, any>) {
    const sub = Boolean(m.parent_tool_use_id);
    switch (m.type) {
      case 'system':
        if (m.subtype === 'init') this.model = m.model;
        if (m.subtype === 'compact_boundary') this.info('context compacted');
        if (m.subtype === 'api_retry') this.info('API retry…', 'yellow');
        return;
      case 'assistant':
        for (const b of m.message?.content ?? []) {
          if (b.type === 'text' && !sub && b.text.trim()) this.say(b.text);
          if (b.type === 'tool_use' || b.type === 'server_tool_use' || b.type === 'mcp_tool_use') {
            const line = `${sub ? '    ' : '  '}⚙ ${b.name} ${describe(b.input ?? {}, this.cwd)}`;
            this.tell({ text: line, color: 'dark_gray', hover: inputHover(b.name, b.input ?? {}) });
          }
        }
        if (m.error) this.info(`error: ${m.error}`, 'red');
        return;
      case 'user':
        for (const b of Array.isArray(m.message?.content) ? m.message.content : []) {
          if (b.type === 'tool_result' && b.is_error && !sub) {
            const text = resultText(b.content);
            this.tell({ text: `  ✗ ${clip(text.split('\n')[0] ?? '', 80)}`, color: 'red', hover: clip(text, 700) });
          }
        }
        return;
      case 'result': {
        if (typeof m.total_cost_usd === 'number') this.cost = m.total_cost_usd;
        const parts = [m.subtype === 'success' ? '✓ done' : `✗ ${String(m.subtype).replace(/_/g, ' ')}`];
        if (m.duration_ms != null) parts.push(`${(m.duration_ms / 1000).toFixed(1)}s`);
        if (this.cost != null) parts.push(`$${this.cost.toFixed(3)} session`);
        this.info(parts.join(' · '), m.is_error ? 'red' : 'dark_gray');
        if (m.errors?.length) this.info(m.errors.join(' '), 'red');
        return;
      }
    }
  }

  // ----- permissions

  private permission(req: { id: string; toolName: string; input: Record<string, unknown>; title?: string }) {
    const p: Pending = { id: req.id, toolName: req.toolName, codes: [] };
    const btn = (label: string, color: string, hover: string, reply: Omit<Extract<ClientMsg, { type: 'permission_response' }>, 'type' | 'id'>) => {
      return this.button(label, color, hover, () => {
        this.deliver({ type: 'permission_response', id: req.id, ...reply });
        this.settle(req.id, reply.allow ? 'allowed' : 'denied');
      }, p);
    };
    const gap = { text: ' ' };
    this.pending.push(p);
    this.npcStatus('needs you', 'gold');

    if (req.toolName === 'AskUserQuestion' && Array.isArray(req.input.questions)) {
      p.questions = req.input.questions as Pending['questions'];
      p.answers = {};
      for (const q of p.questions!) {
        this.tell({ text: '? ', color: 'gold', bold: true }, { text: q.question, bold: true });
        const opts = q.options.map((o, i) => [
          this.button(`${i + 1} ${o.label}`, 'aqua', (o as { description?: string }).description ?? o.label, () => this.answerQuestion(p, q.question, o.label), p),
          gap,
        ]);
        this.tell(...opts.flat());
      }
      this.info('Click an option, or say "claude 1", "claude 2", …');
      return;
    }

    if (req.toolName === 'ExitPlanMode' && typeof req.input.plan === 'string') {
      this.info('Plan ready:', 'gold');
      this.say(req.input.plan);
      this.tell(
        btn('Approve', 'green', 'Start, asking before edits', { allow: true, mode: 'default' }), gap,
        btn('Approve + accept edits', 'yellow', 'Start, edits auto-approved', { allow: true, mode: 'acceptEdits' }), gap,
        btn('Keep planning', 'red', 'Stay in plan mode', { allow: false }),
      );
      return;
    }

    const what = req.title ?? `${req.toolName} ${describe(req.input, this.cwd)}`;
    this.tell({ text: '? ', color: 'gold', bold: true }, { text: clip(what, 120), color: 'yellow', hover: inputHover(req.toolName, req.input) });
    this.tell(
      { text: '  ' },
      btn('Allow', 'green', 'Allow this once', { allow: true }), gap,
      btn('Deny', 'red', 'Deny this', { allow: false }),
      { text: '  or say "claude yes" / "claude no"', color: 'dark_gray' },
    );
  }

  private answerQuestion(p: Pending, question: string, label: string) {
    p.answers![question] = label;
    if (Object.keys(p.answers!).length < p.questions!.length) return;
    this.deliver({ type: 'permission_response', id: p.id, allow: true, answers: p.answers });
    this.settle(p.id, `answered: ${Object.values(p.answers!).join(' / ')}`);
  }

  private settle(id: string, label: string) {
    const i = this.pending.findIndex((p) => p.id === id);
    if (i < 0) return;
    const [p] = this.pending.splice(i, 1);
    p!.codes.forEach((c) => this.buttons.delete(c));
    this.info(`  ${label}`, 'dark_gray');
    this.npcStatus(this.pending.length ? 'needs you' : 'working…', this.pending.length ? 'gold' : 'yellow');
  }

  private clearPending() {
    for (const p of this.pending) p.codes.forEach((c) => this.buttons.delete(c));
    this.pending = [];
  }

  // ----- Minecraft → Conn

  onJoin(player: string) {
    this.command(`scoreboard players enable ${player} claude`);
    this.command(`execute at ${player} unless entity ${NPC} run ${this.summon()}`);
    this.command(`execute as ${NPC} at @s run tp @s ~ ~ ~ facing entity ${player} eyes`);
    this.tell({ text: 'Claude Code', color: ORANGE, bold: true }, { text: ` is here, working in ${this.cwd.replace(os.homedir(), '~')}.`, color: 'gray' });
    this.info('Say "claude <request>" to ask for something, or "claude help".');
    if (this.apiKeyPresent) this.info('Warning: ANTHROPIC_API_KEY is set, so usage bills the API, not your subscription.', 'yellow');
  }

  onTrigger(player: string, code: number) {
    this.command(`scoreboard players enable ${player} claude`);
    const action = this.buttons.get(code);
    if (action) action();
    else this.info('That button has expired.');
  }

  onChat(player: string, text: string) {
    const m = text.match(/^@?claude\b[,:]?\s*(.*)$/i);
    if (!m) return;
    const body = m[1]!.trim();
    const cmd = body.toLowerCase();
    let x: RegExpMatchArray | null;

    if (!body || cmd === 'help') return this.help();
    if (cmd === 'stop') return this.deliver({ type: 'interrupt' });
    if (cmd === 'come') return this.come(player);
    if (cmd === 'status') return this.status();
    if (cmd === 'sessions') return this.listSessions();
    if (cmd === 'new') {
      this.deliver({ type: 'new_session' });
      return this.info('New session. Next request starts fresh.');
    }
    if (['yes', 'y', 'allow'].includes(cmd)) return this.answer(true);
    if (['no', 'n', 'deny'].includes(cmd)) return this.answer(false);
    if ((x = cmd.match(/^mode (ask|edits|accept edits|plan)$/))) {
      const mode = x[1] === 'ask' ? 'default' : x[1] === 'plan' ? 'plan' : 'acceptEdits';
      this.deliver({ type: 'set_mode', mode });
      return this.info(`Mode: ${x[1]}`);
    }
    if ((x = body.match(/^cd ([~/].*)$/i))) {
      this.announceCwd = true;
      return this.deliver({ type: 'set_cwd', cwd: x[1]! });
    }
    if ((x = cmd.match(/^resume (\d+)$/))) {
      const s = this.sessions[Number(x[1]) - 1];
      return s ? this.deliver({ type: 'resume', sessionId: s.id }) : this.info('No session with that number. Try "claude sessions".');
    }
    const q = this.pending.find((p) => p.questions);
    if (q && (x = cmd.match(/^(\d+)$/))) {
      const open = q.questions!.find((qq) => !(qq.question in q.answers!));
      const opt = open?.options[Number(x[1]) - 1];
      return open && opt ? this.answerQuestion(q, open.question, opt.label) : this.info('No such option.');
    }

    if (this.running) return this.info('Claude is still working. Say "claude stop" to interrupt.');
    this.command(`execute as ${NPC} at @s run tp @s ~ ~ ~ facing entity ${player} eyes`);
    this.deliver({ type: 'prompt', text: body });
  }

  private answer(allow: boolean) {
    const p = this.pending.find((pp) => !pp.questions);
    if (!p) return this.info('Nothing is waiting for approval.');
    this.deliver({ type: 'permission_response', id: p.id, allow });
    this.settle(p.id, allow ? 'allowed' : 'denied');
  }

  private summon() {
    const nbt = {
      Tags: ['claude'],
      CustomName: { text: 'Claude', color: ORANGE },
      CustomNameVisible: true,
      description: { text: 'Claude Code', color: 'gray' },
      immovable: true,
      Invulnerable: true,
      PersistenceRequired: true,
    };
    return `summon minecraft:mannequin ^ ^ ^2 ${JSON.stringify(nbt)}`;
  }

  private come(player: string) {
    this.command('kill @e[type=minecraft:mannequin,tag=claude]');
    this.command(`execute at ${player} run ${this.summon()}`);
    this.command(`execute as ${NPC} at @s run tp @s ~ ~ ~ facing entity ${player} eyes`);
  }

  private help() {
    this.tell({ text: 'Claude Code in Minecraft', color: ORANGE, bold: true });
    for (const [c, d] of [
      ['claude <request>', 'ask Claude to do something'],
      ['claude stop', 'interrupt the current run'],
      ['claude yes / no', 'answer a permission request'],
      ['claude mode ask|edits|plan', 'permission mode'],
      ['claude new / sessions / resume <n>', 'manage sessions'],
      ['claude cd <path>', 'change project directory'],
      ['claude status / come', 'show status / call the Claude NPC over'],
    ]) this.tell({ text: `  ${c}`, color: 'aqua' }, { text: `  ${d}`, color: 'gray' });
  }

  private status() {
    const mode = this.mode === 'default' ? 'ask' : this.mode === 'acceptEdits' ? 'accept edits' : this.mode;
    const parts: Part[] = [{ text: `${this.model.replace(/^claude-/, '') || 'model: default'} · ${this.cwd} · mode ${mode}`, color: 'gray' }];
    if (this.sessionId) parts.push({ text: ` · session ${this.sessionId.slice(0, 8)}`, color: 'aqua', hover: 'Click to copy the full ID', copy: this.sessionId });
    if (this.cost != null) parts.push({ text: ` · $${this.cost.toFixed(3)}`, color: 'gray' });
    this.tell(...parts);
  }

  private listSessions() {
    if (!this.sessions.length) return this.info('No sessions in this directory yet.');
    this.info(`Sessions in ${this.cwd}:`);
    this.sessions.slice(0, 6).forEach((s, i) => {
      this.tell(
        { text: `  ${i + 1}. `, color: 'gray' },
        this.button('resume', 'aqua', s.id, () => this.deliver({ type: 'resume', sessionId: s.id })),
        { text: ` ${clip(s.title, 60)}`, color: s.id === this.sessionId ? 'white' : 'gray' },
      );
    });
  }
}
