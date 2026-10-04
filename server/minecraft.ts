/**
 * Claude Code in Minecraft. Runs a local Paper server (127.0.0.1 only) and bridges its chat to a
 * Claude Code session: say "claude <request>" in game; replies, tool calls and [Allow]/[Deny]
 * buttons come back as chat. Input is read from the server console; output goes in as console
 * commands (tellraw), so no bot client or extra packages are needed.
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import readline from 'node:readline';
import { ROOT } from './conn.js';
import { Bridge } from './mcbridge.js';

const MC_VERSION = '1.21.11';
const DIR = path.join(ROOT, 'minecraft/server');
const NAME = /^\w{1,16}$/;

const PROPERTIES = `# Written by claude-ui. Bound to 127.0.0.1: only this Mac can join.
server-ip=127.0.0.1
server-port=25565
online-mode=false
enforce-secure-profile=false
motd=Claude Code
gamemode=creative
force-gamemode=true
difficulty=peaceful
spawn-protection=0
max-players=4
view-distance=8
simulation-distance=6
broadcast-console-to-ops=false
log-ips=false
enable-rcon=false
enable-query=false
`;

// Console feedback from our own commands; kept out of the terminal.
const NOISE =
  /^(No player was found|No entity was found|Modified entity data of|Summoned new|Teleported |Killed |Enabled trigger|Nothing changed|Created new objective|An objective already exists|Showing new (title|actionbar)|\w+ issued server command: \/trigger claude)/;

// ---------- setup

async function setup() {
  fs.mkdirSync(DIR, { recursive: true });
  const jar = path.join(DIR, 'paper.jar');
  if (!fs.existsSync(jar)) await downloadPaper(jar);

  const eula = path.join(DIR, 'eula.txt');
  if (!/^eula=true$/m.test(fs.existsSync(eula) ? fs.readFileSync(eula, 'utf8') : '')) {
    console.log('Running a Minecraft server requires accepting the Minecraft EULA: https://aka.ms/MinecraftEULA');
    if (!/^y/i.test(await ask('Do you accept it? [y/N] '))) process.exit(1);
    fs.writeFileSync(eula, 'eula=true\n');
  }
  const props = path.join(DIR, 'server.properties');
  if (!fs.existsSync(props)) fs.writeFileSync(props, PROPERTIES);
}

async function downloadPaper(dest: string) {
  type Build = { channel: string; downloads: Record<string, { name: string; url: string; size: number; checksums: { sha256: string } }> };
  const builds = (await (await fetch(`https://fill.papermc.io/v3/projects/paper/versions/${MC_VERSION}/builds`)).json()) as Build[];
  const file = builds.find((b) => b.channel === 'STABLE')?.downloads['server:default'];
  if (!file) throw new Error(`No stable Paper build for ${MC_VERSION}`);
  console.log(`Downloading ${file.name} (${(file.size / 1e6).toFixed(1)} MB) from PaperMC…`);
  const data = Buffer.from(await (await fetch(file.url)).arrayBuffer());
  if (crypto.createHash('sha256').update(data).digest('hex') !== file.checksums.sha256) {
    throw new Error('Paper download failed its checksum');
  }
  fs.writeFileSync(dest, data);
}

function ask(question: string) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise<string>((resolve) => rl.question(question, (a) => (rl.close(), resolve(a))));
}

// ---------- main

await setup();

const java = spawn('java', ['-Xms1G', '-Xmx2G', '-jar', 'paper.jar', '--nogui'], { cwd: DIR, stdio: ['pipe', 'pipe', 'inherit'] });
const command = (cmd: string) => java.stdin.writable && java.stdin.write(`${cmd}\n`);
const bridge = new Bridge(command);

// This terminal doubles as the server console.
process.stdin.pipe(java.stdin, { end: false });

readline.createInterface({ input: java.stdout }).on('line', (raw) => {
  const line = raw.replace(/\x1b\[[0-9;]*m/g, '');
  const msg = line.match(/^\[[\d:]+ \w+\]: (.*)$/)?.[1];
  if (!msg || !NOISE.test(msg)) console.log(line);
  if (!msg) return;
  let x: RegExpMatchArray | null;
  if ((x = msg.match(/^(?:\[Not Secure\] )?<(\w{1,16})> (.*)$/))) return bridge.onChat(x[1]!, x[2]!);
  if ((x = msg.match(/^(\w{1,16}) issued server command: \/trigger claude set (\d+)$/))) return bridge.onTrigger(x[1]!, Number(x[2]));
  if ((x = msg.match(/^(\w{1,16}) joined the game$/)) && NAME.test(x[1]!)) return bridge.onJoin(x[1]!);
  if (msg.startsWith('Done (')) {
    command('scoreboard objectives add claude trigger');
    console.log(`\n\x1b[1mReady.\x1b[0m Open Minecraft ${MC_VERSION} → Multiplayer → Direct Connection → 127.0.0.1\n`);
  }
});

let stopping = false;
function shutdown() {
  if (stopping) return;
  stopping = true;
  bridge.stop();
  command('stop');
  setTimeout(() => java.kill('SIGKILL'), 30_000).unref();
}
java.on('exit', (code) => process.exit(code ?? 0));
for (const sig of ['SIGINT', 'SIGTERM'] as const) process.on(sig, shutdown);
