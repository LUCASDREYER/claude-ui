import fs from 'node:fs';

export type SessionEntry = { id: string; title: string; createdAt: number; updatedAt: number };
type Data = { recentDirs: string[]; sessions: Record<string, SessionEntry[]> };

const MAX_RECENT = 12;

/**
 * Session IDs and recent directories, persisted to a local JSON file.
 * Every call reads the file fresh: the web server and the Minecraft bridge both write it.
 */
export class SessionStore {
  constructor(private file: string) {}

  recentDirs() {
    return load(this.file).recentDirs;
  }

  list(cwd: string) {
    return [...(load(this.file).sessions[cwd] ?? [])].sort((a, b) => b.updatedAt - a.updatedAt);
  }

  has(cwd: string, id: string) {
    return load(this.file).sessions[cwd]?.some((s) => s.id === id) ?? false;
  }

  touchDir(cwd: string) {
    this.update((d) => {
      d.recentDirs = [cwd, ...d.recentDirs.filter((x) => x !== cwd)].slice(0, MAX_RECENT);
    });
  }

  /** Records a session; `replaces` renames an existing entry when the SDK hands back a new ID. */
  upsert(cwd: string, id: string, opts: { title?: string; replaces?: string | null } = {}) {
    this.update((d) => {
      const list = (d.sessions[cwd] ??= []);
      const now = Date.now();
      const entry = list.find((s) => s.id === id) ?? (opts.replaces ? list.find((s) => s.id === opts.replaces) : undefined);
      if (entry) {
        entry.id = id;
        entry.updatedAt = now;
      } else {
        list.push({ id, title: titleFrom(opts.title), createdAt: now, updatedAt: now });
      }
    });
  }

  touch(cwd: string, id: string) {
    this.update((d) => {
      const entry = d.sessions[cwd]?.find((s) => s.id === id);
      if (entry) entry.updatedAt = Date.now();
    });
  }

  private update(change: (d: Data) => void) {
    const data = load(this.file);
    change(data);
    const tmp = `${this.file}.${process.pid}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
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
