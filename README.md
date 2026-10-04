# claude-ui

Local web UI for Claude Code (Agent SDK) plus a terminal status line. Uses your `claude` login, not an API key.

1. `claude auth login` once, if you aren't logged in. Keep `ANTHROPIC_API_KEY` unset.
2. `npm install && npm start`: serves http://127.0.0.1:3456 and opens it as a Chrome app window, no tabs or address bar (`PORT=…` to change, `NO_OPEN=1` to skip).
3. Pick a directory in the sidebar, chat; `Esc` stops a run; ask / accept edits / plan below the input.
4. Status line: add to `~/.claude/settings.json` →
   `"statusLine": { "type": "command", "command": "python3 \"/path/to/claude-ui/statusline/statusline.py\"" }`
5. macOS app: `npm run app` builds `Claude UI.app` into `~/Applications` (needs Xcode). It runs the server itself; quitting stops it.
6. Minecraft: `npm run minecraft` starts a local Paper 1.21.11 server (127.0.0.1 only). Join with Minecraft 1.21.11 → Direct Connection → `127.0.0.1`, then chat `claude <request>`. `claude panel` opens a Claude window, `claude menu` a controls menu, `claude help` lists the rest.
7. Minecraft mod: `npm run mod` builds the Fabric mod into your mods folder. Launcher profile **fabric-loader-1.21.11**, any world, press **K** (needs the Claude UI app or `npm start` running).
8. Claude Screen block (single-player): build a flat wall of them, 1.3–3× wider than tall and up to 128×72 (8×4, 16×9, 48×20 cinema…), and it shows the UI. Aim at it to use it: left-click clicks, the wheel scrolls, click the prompt bar to type, right-click opens the full UI, sneak to build or break. Creative tab: Functional Blocks; recipe: 5 black concrete + glass pane + redstone → 4.
