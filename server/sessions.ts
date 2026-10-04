import fs from 'node:fs';

export type SessionEntry = { id: string; title: string; createdAt: number; updatedAt: number };
type Data = { recentDirs: string[]; sessions: Record<string, SessionEntry[]> };

const MAX_RECENT = 12;

/** Session IDs and recent directories, persisted to a local JSON file. */
export class SessionStore {
  private data: Data;

  constructor(private file: string) {
    this.data = load(file);
  }

  recentDirs() {
    return this.data.recentDirs;
  }

  list(cwd: string) {
    return [...(this.data.sessions[cwd] ?? [])].sort((a, b) => b.updatedAt - a.updatedAt);
  }

  has(cwd: string, id: string) {
    return this.data.sessions[cwd]?.some((s) => s.id === id) ?? false;
  }

  touchDir(cwd: string) {
    this.data.recentDirs = [cwd, ...this.data.recentDirs.filter((d) => d !== cwd)].slice(0, MAX_RECENT);
    this.save();
  }

  /** Records a session; `replaces` renames an existing entry when the SDK hands back a new ID. */
  upsert(cwd: string, id: string, opts: { title?: string; replaces?: string | null } = {}) {
    const list = (this.data.sessions[cwd] ??= []);
    const now = Date.now();
    const entry = list.find((s) => s.id === id) ?? (opts.replaces ? list.find((s) => s.id === opts.replaces) : undefined);
    if (entry) {
      entry.id = id;
      entry.updatedAt = now;
    } else {
      list.push({ id, title: titleFrom(opts.title), createdAt: now, updatedAt: now });
    }
    this.save();
  }

  touch(cwd: string, id: string) {
    const entry = this.data.sessions[cwd]?.find((s) => s.id === id);
    if (!entry) return;
    entry.updatedAt = Date.now();
    this.save();
  }

  private save() {
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2));
    fs.renameSync(tmp, this.file);
  }
}

function titleFrom(text = '') {
  const line = text.trim().split('\n')[0] ?? '';
  return line.length > 80 ? `${line.slice(0, 79)}…` : line || 'Untitled';
}

function load(file: string): Data {
  try {
    const d = JSON.parse(fs.readFileSync(file, 'utf8'));
    return {
      recentDirs: Array.isArray(d.recentDirs) ? d.recentDirs : [],
      sessions: d.sessions && typeof d.sessions === 'object' ? d.sessions : {},
    };
  } catch {
    return { recentDirs: [], sessions: {} };
  }
}
