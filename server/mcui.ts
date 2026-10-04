/**
 * Layouts for the in-game UI: the chest-style item menu (opened by the ClaudeUI plugin) and the
 * dialog panel (shown with the vanilla /dialog command). Pure functions of the bridge's state.
 */
import os from 'node:os';
import type { SessionEntry } from './sessions.js';

export const ORANGE = '#D97757';

export type Entry = { kind: 'you' | 'claude' | 'tool' | 'info' | 'error'; text: string };

export type PendingView = {
  toolName: string;
  what: string;
  detail: string;
  plan?: string;
  questions?: { question: string; options: { label: string; description?: string }[] }[];
};

export type UiState = {
  cwd: string;
  model: string;
  mode: string;
  running: boolean;
  cost: number | null;
  sessionId: string | null;
  sessions: SessionEntry[];
  transcript: Entry[];
  pending: PendingView | null;
};

export const MODES: [id: string, label: string, material: string, hint: string][] = [
  ['default', 'ask', 'book', 'Ask before edits and commands'],
  ['acceptEdits', 'accept edits', 'feather', 'Edits go through; commands still ask'],
  ['plan', 'plan', 'map', 'Read and plan only, no changes'],
];

const tilde = (p: string) => p.replace(os.homedir(), '~');
const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, n - 1)}…` : s);
const modeLabel = (mode: string) => MODES.find(([id]) => id === mode)?.[1] ?? mode;

export function ago(ms: number) {
  const s = (Date.now() - ms) / 1000;
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

// ---------- item menus

type Item = { slot: number; material: string; name: string; color?: string; lore?: string[]; id?: string; glow?: boolean; close?: boolean };
export type MenuSpec = { name: string; title: string; rows: number; items: Item[] };

function withFiller(items: Item[], rows: number): Item[] {
  const used = new Set(items.map((i) => i.slot));
  const filler = Array.from({ length: rows * 9 }, (_, slot) => slot)
    .filter((slot) => !used.has(slot))
    .map((slot) => ({ slot, material: 'black_stained_glass_pane', name: ' ' }));
  return [...items, ...filler];
}

/**
 *  row 0:  message · ask · accept edits · plan · stop
 *  row 1:  new · sessions · status · call NPC · help
 *  row 2:  allow · request · deny   (only while something waits for you)
 */
export function mainMenu(s: UiState): MenuSpec {
  const items: Item[] = [
    { slot: 1, material: 'writable_book', name: 'Message Claude', color: ORANGE, lore: ['Open the Claude panel'], id: 'panel', glow: true },
    ...MODES.map(([id, label, material, hint], i): Item => ({
      slot: 3 + i,
      material,
      name: `Mode: ${label}`,
      color: s.mode === id ? 'green' : 'white',
      lore: [hint, s.mode === id ? 'Active' : 'Click to switch'],
      id: `mode:${id}`,
      glow: s.mode === id,
    })),
    s.running
      ? { slot: 7, material: 'barrier', name: 'Stop', color: 'red', lore: ['Claude is working', 'Click to interrupt'], id: 'stop' }
      : { slot: 7, material: 'gray_dye', name: 'Stop', color: 'dark_gray', lore: ['Nothing is running'] },
    { slot: 10, material: 'paper', name: 'New session', lore: ['Start fresh in this directory'], id: 'new' },
    { slot: 11, material: 'bookshelf', name: 'Sessions', lore: [`${s.sessions.length} in ${tilde(s.cwd)}`], id: 'sessions' },
    {
      slot: 13,
      material: 'compass',
      name: 'Status',
      color: 'aqua',
      lore: [
        s.model.replace(/^claude-/, '') || 'model: default',
        clip(tilde(s.cwd), 48),
        s.sessionId ? `session ${s.sessionId.slice(0, 8)}` : 'new session',
        s.cost != null ? `$${s.cost.toFixed(3)} this session (est.)` : 'no cost yet',
        s.running ? 'working…' : 'idle',
      ],
      id: 'status',
      close: true,
    },
    { slot: 15, material: 'ender_pearl', name: 'Call Claude here', lore: ['Brings the Claude NPC to you'], id: 'come', close: true },
    { slot: 16, material: 'oak_sign', name: 'Help', lore: ['Chat commands'], id: 'help', close: true },
  ];

  const p = s.pending;
  if (!p) {
    items.push({ slot: 22, material: 'light_gray_dye', name: 'Nothing waiting for you', color: 'gray' });
  } else if (p.questions) {
    items.push({ slot: 22, material: 'writable_book', name: 'Claude has a question', color: 'gold', lore: ['Open the panel to answer'], id: 'panel', glow: true });
  } else if (p.plan != null) {
    items.push(
      { slot: 20, material: 'lime_dye', name: 'Approve plan', color: 'green', lore: ['Then ask before edits'], id: 'approve' },
      { slot: 22, material: 'filled_map', name: 'Plan ready', color: 'gold', lore: ['Open the panel to read it'], id: 'panel', glow: true },
      { slot: 24, material: 'red_dye', name: 'Keep planning', color: 'red', id: 'deny' },
    );
  } else {
    items.push(
      { slot: 20, material: 'lime_dye', name: 'Allow', color: 'green', lore: [clip(p.what, 48)], id: 'allow' },
      { slot: 22, material: 'name_tag', name: clip(p.what, 40), color: 'yellow', lore: p.detail.split('\n').slice(0, 8).map((l) => clip(l, 48)), glow: true },
      { slot: 24, material: 'red_dye', name: 'Deny', color: 'red', lore: [clip(p.what, 48)], id: 'deny' },
    );
  }
  return { name: 'main', title: s.running ? 'Claude Code · working' : 'Claude Code', rows: 3, items: withFiller(items, 3) };
}

export function sessionsMenu(s: UiState): MenuSpec {
  const items: Item[] = s.sessions.slice(0, 27).map((session, i) => ({
    slot: i,
    material: session.id === s.sessionId ? 'enchanted_book' : 'book',
    name: clip(session.title, 40),
    lore: [ago(session.updatedAt), session.id.slice(0, 8), session.id === s.sessionId ? 'Current session' : 'Click to resume'],
    id: `resume:${i}`,
    close: true,
  }));
  if (!items.length) items.push({ slot: 13, material: 'barrier', name: 'No sessions here yet', color: 'gray' });
  items.push({ slot: 31, material: 'arrow', name: 'Back', id: 'back' });
  return { name: 'sessions', title: `Sessions · ${clip(tilde(s.cwd), 30)}`, rows: 4, items: withFiller(items, 4) };
}

// ---------- dialogs

type Text = { text: string; color?: string; bold?: boolean; italic?: boolean };
const custom = (id: string) => ({ type: 'minecraft:custom', id: `claude:${id}` });
const submit = (id: string) => ({ type: 'minecraft:dynamic/custom', id: `claude:${id}` });
const button = (label: string, action: object, tooltip?: string, width = 96) => ({
  label: { text: label },
  ...(tooltip && { tooltip: { text: tooltip } }),
  width,
  action,
});
const message = (contents: Text[], width = 400) => ({ type: 'minecraft:plain_message', contents, width });

function transcriptText(entries: Entry[], maxLines = 36): Text[] {
  if (!entries.length) return [{ text: 'Ask Claude anything below.', color: 'gray' }];
  const lines: Text[] = [];
  for (const e of entries) {
    for (const [i, line] of e.text.split('\n').entries()) {
      if (e.kind === 'you') lines.push({ text: `${i ? '  ' : '> '}${line}`, color: 'aqua' });
      else if (e.kind === 'tool') lines.push({ text: `  ${line}`, color: 'dark_gray' });
      else if (e.kind === 'info') lines.push({ text: line, color: 'gray' });
      else if (e.kind === 'error') lines.push({ text: line, color: 'red' });
      else lines.push({ text: line });
    }
  }
  return lines.slice(-maxLines).flatMap((l, i) => (i ? [{ text: '\n' }, l] : [l]));
}

/** The main panel: status, conversation, then either a message box, a working note, or the request waiting on you. */
export function panelDialog(s: UiState) {
  const status = [tilde(s.cwd), s.model.replace(/^claude-/, '') || 'default model', `mode ${modeLabel(s.mode)}`];
  if (s.cost != null) status.push(`$${s.cost.toFixed(3)}`);
  const body: object[] = [message([{ text: status.join('  ·  '), color: 'gray' }]), message(transcriptText(s.transcript))];
  let inputs: object[] = [];
  let actions: object[];
  const p = s.pending;

  if (p?.questions) {
    body.push(message([{ text: 'Claude has a question', color: 'gold', bold: true }]));
    inputs = p.questions.slice(0, 4).map((q, i) => ({
      type: 'minecraft:single_option',
      key: `q${i}`,
      label: { text: clip(q.question, 60) },
      width: 400,
      options: q.options.map((o, j) => ({ id: String(j), display: { text: o.label }, initial: j === 0 })),
    }));
    actions = [button('Answer', submit('answer')), button('Skip', custom('deny'))];
  } else if (p?.plan != null) {
    body.push(message([{ text: 'Plan ready', color: 'gold', bold: true }, { text: '\n' }, { text: p.plan }]));
    actions = [
      button('Approve', custom('approve'), 'Start, asking before edits'),
      button('Approve + edits', custom('approve_edits'), 'Start with edits auto-approved', 120),
      button('Keep planning', custom('deny'), undefined, 110),
    ];
  } else if (p) {
    body.push(message([{ text: `Claude wants to: ${p.what}`, color: 'yellow', bold: true }, { text: '\n' }, { text: clip(p.detail, 900), color: 'gray' }]));
    actions = [button('Allow', custom('allow')), button('Deny', custom('deny'))];
  } else if (s.running) {
    body.push(message([{ text: 'Claude is working…', color: 'yellow' }]));
    actions = [button('Stop', custom('stop'), 'Interrupt the current run'), button('Menu', custom('menu'))];
  } else {
    inputs = [
      {
        type: 'minecraft:text',
        key: 'prompt',
        label: { text: 'Message' },
        width: 400,
        max_length: 4000,
        multiline: { max_lines: 8, height: 90 },
      },
      {
        type: 'minecraft:single_option',
        key: 'mode',
        label: { text: 'Mode' },
        width: 200,
        options: MODES.map(([id, label]) => ({ id, display: { text: label }, initial: id === s.mode })),
      },
    ];
    actions = [
      button('Send', submit('send'), 'Send the message'),
      button('New session', submit('new')),
      button('Sessions', submit('sessions')),
      button('Menu', submit('menu')),
    ];
  }

  return {
    type: 'minecraft:multi_action',
    title: { text: 'Claude Code', color: ORANGE },
    external_title: { text: 'Claude Code' },
    body,
    inputs,
    actions,
    columns: 4,
    can_close_with_escape: true,
    pause: false,
    after_action: 'none',
    exit_action: button('Close', custom('closed')),
  };
}

export function sessionsDialog(s: UiState) {
  const actions = s.sessions.slice(0, 12).map((session, i) =>
    button(clip(session.title, 40), custom(`resume/${i}`), `${ago(session.updatedAt)} · ${session.id.slice(0, 8)}`, 300),
  );
  return {
    type: 'minecraft:multi_action',
    title: { text: 'Sessions', color: ORANGE },
    body: [message([{ text: tilde(s.cwd), color: 'gray' }])],
    actions: actions.length ? actions : [button('No sessions yet', custom('panel'), undefined, 300)],
    columns: 1,
    can_close_with_escape: true,
    pause: false,
    after_action: 'none',
    exit_action: button('Back', custom('panel')),
  };
}
