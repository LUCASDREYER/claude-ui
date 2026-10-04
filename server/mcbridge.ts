/** Minecraft side of the bridge: turns chat into Conn messages and Conn events into tellraw commands. */
import os from 'node:os';
import { Conn, type ClientMsg } from './conn.js';
import type { SessionEntry } from './sessions.js';
import { ORANGE, mainMenu, panelDialog, sessionsDialog, sessionsMenu, type Entry, type UiState } from './mcui.js';
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

type Pending = {
  id: string;
  toolName: string;
  what: string;
  detail: string;
  plan?: string;
  codes: number[];
  questions?: { question: string; options: { label: string; description?: string }[] }[];
  answers?: Record<string, string>;
};
type Reply = Omit<Extract<ClientMsg, { type: 'permission_response' }>, 'type' | 'id'>;

const b64 = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64');

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
  // In-game UI: conversation shown in the panel, who is using it, and whether the panel is up.
  private transcript: Entry[] = [];
  private uiPlayer = '';
  private panelOpen = false;
  private refreshTimer: NodeJS.Timeout | null = null;

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

  private record(kind: Entry['kind'], text: string) {
    this.transcript.push({ kind, text });
    if (this.transcript.length > 80) this.transcript.splice(0, this.transcript.length - 80);
  }

  private say(md: string) {
    this.record('claude', chatLines(md, 24).map((l) => l.text).join('\n'));
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
        this.transcript = [];
        return this.refreshUi();
      case 'error':
        this.announceCwd = false;
        this.record('error', String(m.message));
        this.info(String(m.message), 'red');
        return this.refreshUi();
      case 'session':
        if (!m.sessionId) this.transcript = [];
        this.sessionId = (m.sessionId as string | null) ?? null;
        this.sessions = m.sessions as SessionEntry[];
        return this.refreshUi();
      case 'mode':
        this.mode = String(m.mode);
        return this.refreshUi();
      case 'running':
        this.running = Boolean(m.running);
        if (this.running) this.npcStatus('working…', 'yellow');
        else this.npcStatus('Claude Code', 'gray');
        if (!this.running) this.clearPending();
        return this.refreshUi();
      case 'history': {
        const title = this.sessions.find((s) => s.id === m.sessionId)?.title ?? String(m.sessionId).slice(0, 8);
        this.transcript = historyEntries(m.messages as Record<string, any>[]);
        this.record('info', `Resumed "${title}".`);
        this.info(`Resumed "${title}" (${(m.messages as unknown[]).length} messages). Carry on.`);
        return this.refreshUi();
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
            if (!sub) this.record('tool', line.trim());
          }
        }
        if (m.error) this.info(`error: ${m.error}`, 'red');
        return;
      case 'user':
        for (const b of Array.isArray(m.message?.content) ? m.message.content : []) {
          if (b.type === 'tool_result' && b.is_error && !sub) {
            const text = resultText(b.content);
            this.tell({ text: `  ✗ ${clip(text.split('\n')[0] ?? '', 80)}`, color: 'red', hover: clip(text, 700) });
            this.record('error', `✗ ${clip(text.split('\n')[0] ?? '', 80)}`);
          }
        }
        return;
      case 'result': {
        if (typeof m.total_cost_usd === 'number') this.cost = m.total_cost_usd;
        const parts = [m.subtype === 'success' ? '✓ done' : `✗ ${String(m.subtype).replace(/_/g, ' ')}`];
        if (m.duration_ms != null) parts.push(`${(m.duration_ms / 1000).toFixed(1)}s`);
        if (this.cost != null) parts.push(`$${this.cost.toFixed(3)} session`);
        this.info(parts.join(' · '), m.is_error ? 'red' : 'dark_gray');
        this.record(m.is_error ? 'error' : 'info', parts.join(' · '));
        if (m.errors?.length) this.info(m.errors.join(' '), 'red');
        return this.refreshUi();
      }
    }
  }

  // ----- permissions

  private permission(req: { id: string; toolName: string; input: Record<string, unknown>; title?: string }) {
    const p: Pending = {
      id: req.id,
      toolName: req.toolName,
      what: req.title ?? `${req.toolName} ${describe(req.input, this.cwd)}`,
      detail: inputHover(req.toolName, req.input),
      codes: [],
    };
    const btn = (label: string, color: string, hover: string, reply: Reply) =>
      this.button(label, color, hover, () => this.respond(p, reply), p);
    const gap = { text: ' ' };
    this.pending.push(p);
    this.npcStatus('needs you', 'gold');
    this.refreshUi();

    if (req.toolName === 'AskUserQuestion' && Array.isArray(req.input.questions)) {
      p.questions = req.input.questions as Pending['questions'];
      p.answers = {};
      this.refreshUi();
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
      p.plan = req.input.plan;
      this.refreshUi();
      this.info('Plan ready:', 'gold');
      this.say(req.input.plan);
      this.tell(
        btn('Approve', 'green', 'Start, asking before edits', { allow: true, mode: 'default' }), gap,
        btn('Approve + accept edits', 'yellow', 'Start, edits auto-approved', { allow: true, mode: 'acceptEdits' }), gap,
        btn('Keep planning', 'red', 'Stay in plan mode', { allow: false }),
      );
      return;
    }

    this.tell({ text: '? ', color: 'gold', bold: true }, { text: clip(p.what, 120), color: 'yellow', hover: p.detail });
    this.tell(
      { text: '  ' },
      btn('Allow', 'green', 'Allow this once', { allow: true }), gap,
      btn('Deny', 'red', 'Deny this', { allow: false }),
      { text: '  or say "claude yes" / "claude no"', color: 'dark_gray' },
    );
  }

  private respond(p: Pending, reply: Reply) {
    this.deliver({ type: 'permission_response', id: p.id, ...reply });
    const label = reply.answers ? `answered: ${Object.values(reply.answers).join(' / ')}` : reply.allow ? 'allowed' : 'denied';
    this.settle(p.id, label);
  }

  private answerQuestion(p: Pending, question: string, label: string) {
    p.answers![question] = label;
    if (Object.keys(p.answers!).length < p.questions!.length) return;
    this.respond(p, { allow: true, answers: p.answers });
  }

  private settle(id: string, label: string) {
    const i = this.pending.findIndex((p) => p.id === id);
    if (i < 0) return;
    const [p] = this.pending.splice(i, 1);
    p!.codes.forEach((c) => this.buttons.delete(c));
    this.info(`  ${label}`, 'dark_gray');
    this.record('info', label);
    this.npcStatus(this.pending.length ? 'needs you' : 'working…', this.pending.length ? 'gold' : 'yellow');
    this.refreshUi();
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
    this.uiPlayer = player;
    this.panelOpen = false; // typing in chat means the panel was closed

    if (!body || cmd === 'help') return this.help();
    if (cmd === 'menu') return this.openMenu(player);
    if (cmd === 'panel' || cmd === 'ui') return this.showPanel(player);
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

    this.prompt(player, body);
  }

  private prompt(player: string, text: string) {
    if (this.running) return this.info('Claude is still working. Say "claude stop" to interrupt.');
    this.command(`execute as ${NPC} at @s run tp @s ~ ~ ~ facing entity ${player} eyes`);
    this.record('you', text);
    this.deliver({ type: 'prompt', text });
  }

  private answer(allow: boolean, mode?: string) {
    const p = this.pending.find((pp) => !pp.questions);
    if (!p) return this.info('Nothing is waiting for approval.');
    this.respond(p, allow && mode && p.plan != null ? { allow, mode: mode as Reply['mode'] } : { allow });
  }

  // ----- in-game UI (item menu via the ClaudeUI plugin, panel via /dialog)

  private state(): UiState {
    const p = this.pending[0];
    return {
      cwd: this.cwd,
      model: this.model,
      mode: this.mode,
      running: this.running,
      cost: this.cost,
      sessionId: this.sessionId,
      sessions: this.sessions,
      transcript: this.transcript,
      pending: p ? { toolName: p.toolName, what: p.what, detail: p.detail, plan: p.plan, questions: p.questions } : null,
    };
  }

  private openMenu(player: string) {
    this.uiPlayer = player;
    this.panelOpen = false;
    this.command(`claudeui open ${player} ${b64(mainMenu(this.state()))}`);
  }

  private showPanel(player: string) {
    this.uiPlayer = player;
    this.panelOpen = true;
    this.command(`dialog show ${player} ${JSON.stringify(panelDialog(this.state()))}`);
  }

  /** Redraws whatever UI is up, batched so a burst of events redraws once. */
  private refreshUi() {
    if (!this.uiPlayer || this.refreshTimer) return;
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      this.command(`claudeui update ${this.uiPlayer} ${b64(mainMenu(this.state()))}`);
      if (this.panelOpen) this.command(`dialog show ${this.uiPlayer} ${JSON.stringify(panelDialog(this.state()))}`);
    }, 250);
  }

  onMenuClick(player: string, menu: string, id: string) {
    this.uiPlayer = player;
    let x: RegExpMatchArray | null;
    if (menu === 'sessions') {
      if (id === 'back') return this.openMenu(player);
      const s = (x = id.match(/^resume:(\d+)$/)) && this.sessions[Number(x[1])];
      if (s && s.id !== this.sessionId) this.deliver({ type: 'resume', sessionId: s.id });
      return;
    }
    if ((x = id.match(/^mode:(default|acceptEdits|plan)$/))) return this.deliver({ type: 'set_mode', mode: x[1] as Reply['mode'] & string });
    switch (id) {
      case 'panel': return this.showPanel(player);
      case 'stop': return this.deliver({ type: 'interrupt' });
      case 'new':
        this.deliver({ type: 'new_session' });
        return this.info('New session. Next request starts fresh.');
      case 'sessions': return this.command(`claudeui open ${player} ${b64(sessionsMenu(this.state()))}`);
      case 'status': return this.status();
      case 'come': return this.come(player);
      case 'help': return this.help();
      case 'allow': return this.answer(true);
      case 'approve': return this.answer(true, 'default');
      case 'deny': return this.answer(false);
    }
  }

  onDialog(player: string, id: string, inputs: Record<string, string>) {
    this.uiPlayer = player;
    if (inputs.mode && inputs.mode !== this.mode && ['default', 'acceptEdits', 'plan'].includes(inputs.mode)) {
      this.deliver({ type: 'set_mode', mode: inputs.mode as Reply['mode'] & string });
    }
    let x: RegExpMatchArray | null;
    if ((x = id.match(/^resume\/(\d+)$/))) {
      const s = this.sessions[Number(x[1])];
      if (s && s.id !== this.sessionId) this.deliver({ type: 'resume', sessionId: s.id });
      return this.showPanel(player);
    }
    switch (id) {
      case 'send': {
        const text = (inputs.prompt ?? '').trim();
        if (text) this.prompt(player, text);
        return this.showPanel(player);
      }
      case 'new':
        this.deliver({ type: 'new_session' });
        return this.showPanel(player);
      case 'sessions':
        this.panelOpen = true;
        return this.command(`dialog show ${player} ${JSON.stringify(sessionsDialog(this.state()))}`);
      case 'panel': return this.showPanel(player);
      case 'menu': return this.openMenu(player);
      case 'stop': return this.deliver({ type: 'interrupt' });
      case 'allow': return this.answer(true);
      case 'approve': return this.answer(true, 'default');
      case 'approve_edits': return this.answer(true, 'acceptEdits');
      case 'deny': {
        const p = this.pending[0];
        return p ? this.respond(p, { allow: false }) : undefined;
      }
      case 'answer': {
        const p = this.pending.find((pp) => pp.questions);
        if (!p) return;
        const answers: Record<string, string> = {};
        p.questions!.slice(0, 4).forEach((q, i) => {
          const choice = q.options[Number(inputs[`q${i}`] ?? 0)];
          if (choice) answers[q.question] = choice.label;
        });
        return this.respond(p, { allow: true, answers });
      }
      case 'closed':
        this.panelOpen = false;
        return this.command(`claudeui closedialog ${player}`);
    }
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
      ['claude panel', 'open the Claude window (write long messages)'],
      ['claude menu', 'open the controls menu'],
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

/** Rebuilds the panel's conversation from a resumed session's transcript. */
function historyEntries(messages: Record<string, any>[]): Entry[] {
  const out: Entry[] = [];
  for (const m of messages.slice(-60)) {
    const content = m.message?.content;
    if (m.type === 'user' && !m.parent_tool_use_id) {
      const text = typeof content === 'string' ? content : Array.isArray(content) ? content.filter((b) => b.type === 'text').map((b) => b.text).join('\n') : '';
      if (text.trim() && !/^\s*<[a-z-]+>/i.test(text)) out.push({ kind: 'you', text: clip(text.trim(), 300) });
    } else if (m.type === 'assistant' && Array.isArray(content)) {
      for (const b of content) {
        if (b.type === 'text' && b.text.trim()) out.push({ kind: 'claude', text: chatLines(b.text, 12).map((l) => l.text).join('\n') });
        if (b.type === 'tool_use') out.push({ kind: 'tool', text: `⚙ ${b.name}` });
      }
    }
  }
  return out;
}
