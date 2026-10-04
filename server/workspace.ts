/**
 * Cross-project data for richer clients (the Minecraft mod): recent sessions from every project,
 * the projects themselves, the user's open pull requests (via gh), and git worktrees.
 */
import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { listSessions } from '@anthropic-ai/claude-agent-sdk';

const run = promisify(execFile);
const TEMP = [os.tmpdir(), '/tmp', '/private/tmp', '/private/var/folders'].map((t) => `${t.replace(/\/$/, '')}/`);
const isTemp = (dir: string) => TEMP.some((t) => `${dir}/`.startsWith(t));

export type RecentSession = { id: string; title: string; cwd: string; project: string; repo: string | null; branch: string | null; updatedAt: number };
export type Project = { path: string; name: string; repo: string | null; branch: string | null; sessions: number; updatedAt: number };
export type PullRequest = { number: number; title: string; repo: string; url: string; draft: boolean; updatedAt: number };

const repoCache = new Map<string, Promise<string | null>>();

/** owner/name of the origin remote, if it is on GitHub-like hosting. */
function repoOf(dir: string) {
  let cached = repoCache.get(dir);
  if (!cached) {
    cached = run('git', ['-C', dir, 'remote', 'get-url', 'origin'], { timeout: 3000 })
      .then(({ stdout }) => stdout.trim().match(/[:/]([^/:]+\/[^/]+?)(?:\.git)?$/)?.[1] ?? null)
      .catch(() => null);
    repoCache.set(dir, cached);
  }
  return cached;
}

async function sessionsAcrossProjects(limit: number) {
  const all = await listSessions({ limit: limit * 2 });
  return all.filter((s) => s.cwd && !isTemp(s.cwd)).slice(0, limit);
}

export async function recentSessions(limit = 12): Promise<RecentSession[]> {
  const sessions = await sessionsAcrossProjects(limit);
  return Promise.all(
    sessions.map(async (s) => ({
      id: s.sessionId,
      title: s.customTitle ?? s.summary,
      cwd: s.cwd!,
      project: path.basename(s.cwd!),
      repo: await repoOf(s.cwd!),
      branch: s.gitBranch && s.gitBranch !== 'HEAD' ? s.gitBranch : null,
      updatedAt: s.lastModified,
    })),
  );
}

export async function projects(extraDirs: string[] = []): Promise<Project[]> {
  const byDir = new Map<string, Project>();
  for (const s of await sessionsAcrossProjects(300)) {
    const p = byDir.get(s.cwd!) ?? { path: s.cwd!, name: path.basename(s.cwd!), repo: null, branch: null, sessions: 0, updatedAt: 0 };
    p.sessions += 1;
    if (s.lastModified > p.updatedAt) {
      p.updatedAt = s.lastModified;
      p.branch = s.gitBranch && s.gitBranch !== 'HEAD' ? s.gitBranch : p.branch;
    }
    byDir.set(s.cwd!, p);
  }
  for (const dir of extraDirs) {
    if (!byDir.has(dir) && !isTemp(dir)) byDir.set(dir, { path: dir, name: path.basename(dir), repo: null, branch: null, sessions: 0, updatedAt: 0 });
  }
  const list = [...byDir.values()].sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 30);
  await Promise.all(list.map(async (p) => (p.repo = await repoOf(p.path))));
  return list;
}

/** Open PRs authored by the gh-authenticated user. */
export async function pullRequests(): Promise<PullRequest[]> {
  const { stdout } = await run(
    'gh',
    ['search', 'prs', '--author=@me', '--state=open', '--limit', '20', '--json', 'number,title,repository,url,updatedAt,isDraft'],
    { timeout: 15000 },
  );
  type Raw = { number: number; title: string; repository: { nameWithOwner: string }; url: string; updatedAt: string; isDraft: boolean };
  return (JSON.parse(stdout) as Raw[])
    .map((pr) => ({ number: pr.number, title: pr.title, repo: pr.repository.nameWithOwner, url: pr.url, draft: pr.isDraft, updatedAt: Date.parse(pr.updatedAt) }))
    .sort((a, b) => b.updatedAt - a.updatedAt);
}

/**
 * Creates <repo>/.claude/worktrees/<name> on a new branch claude/<name> and returns its path.
 * The folder is excluded locally (.git/info/exclude), so the main checkout stays clean.
 */
export async function createWorktree(dir: string) {
  const git = (...args: string[]) => run('git', ['-C', dir, ...args], { timeout: 15000 }).then((r) => r.stdout.trim());
  const root = await git('rev-parse', '--show-toplevel').catch(() => {
    throw new Error(`${dir} is not a git repository, so it can't have a worktree.`);
  });
  const name = new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15);
  const target = path.join(root, '.claude', 'worktrees', name);
  await git('worktree', 'add', '-b', `claude/${name}`, target);
  const exclude = path.join(await git('rev-parse', '--git-common-dir').then((d) => path.resolve(root, d)), 'info', 'exclude');
  const current = await fs.readFile(exclude, 'utf8').catch(() => '');
  if (!current.includes('.claude/worktrees/')) {
    await fs.mkdir(path.dirname(exclude), { recursive: true });
    await fs.appendFile(exclude, `${current && !current.endsWith('\n') ? '\n' : ''}.claude/worktrees/\n`);
  }
  return target;
}
