# claude-ui

Local web UI for Claude Code (Agent SDK) plus a terminal status line. Uses your `claude` login, not an API key.

1. `claude auth login` once, if you aren't logged in. Keep `ANTHROPIC_API_KEY` unset.
2. `npm install && npm start`: serves http://127.0.0.1:3456 and opens it (`PORT=…` to change, `NO_OPEN=1` to skip).
3. Pick a directory in the sidebar, chat; `Esc` stops a run; ask / accept edits / plan below the input.
4. Status line: add to `~/.claude/settings.json` →
   `"statusLine": { "type": "command", "command": "python3 \"/path/to/claude-ui/statusline/statusline.py\"" }`
