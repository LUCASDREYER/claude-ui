package claudeui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Client-side mirror of the claude-ui session; everything the screen draws. Touched only on the client thread. */
public final class Model {
	public enum Kind { USER, TEXT, TOOL, INFO, ERROR }

	public static final class Entry {
		public final Kind kind;
		public final StringBuilder text = new StringBuilder();
		final StringBuilder rawInput = new StringBuilder();
		public String toolId = "", toolName = "", detail = "", output = "";
		public int state; // tools: 0 running, 1 done, 2 failed, 3 stopped
		public boolean sub, expanded;
		// wrapped lines, rebuilt when the width or content changes
		int cacheWidth = -1, cacheLength = -1, cacheState = -1;
		boolean cacheExpanded;
		List<FormattedCharSequence> lines = List.of();

		Entry(Kind kind, String text) {
			this.kind = kind;
			this.text.append(text);
		}
	}

	public record Question(String question, List<String> options, List<String> descriptions) {}

	public static final class Pending {
		public String id = "", toolName = "", what = "", detail = "";
		public String plan;
		public List<Question> questions;
		public final Map<String, String> answers = new HashMap<>();
	}

	public record Recent(String id, String title, String cwd, String project, String repo, String branch, long updatedAt) {}
	public record Project(String path, String name, String repo, String branch, int sessions, long updatedAt) {}
	public record Pr(int number, String title, String repo, String url, boolean draft, long updatedAt) {}
	public record ModelOption(String value, String name, String description) {}
	public record FileEntry(String name, boolean dir) {}

	private final Connection conn;
	public boolean connected;
	public String problem = "";
	public boolean apiKeyPresent;
	public String homeDir = "", cwd = "", mode = "default", model = "default", modelName = "";
	public String sessionId;
	public boolean running;
	public Double cost;
	public final List<Entry> transcript = new ArrayList<>();
	public final List<Pending> pending = new ArrayList<>();
	public List<Recent> recent = List.of();
	public List<Project> projects = List.of();
	public boolean homeLoaded;
	public List<Pr> prs = List.of();
	public String prsError;
	public boolean prsLoaded;
	public List<ModelOption> models = List.of();
	public String lsPath;
	public List<FileEntry> lsEntries = List.of();

	private String promptAfterNew;
	private final Map<String, Entry> blocks = new HashMap<>();
	private final Map<String, Entry> tools = new HashMap<>();
	private final Set<String> streamed = new HashSet<>();
	private String currentMessage = "";
	private String lastText = "";

	Model(Connection conn) {
		this.conn = conn;
	}

	// ---------- requests

	public void refreshHome() {
		conn.send("home", "limit", 40);
		conn.send("prs");
		if (models.isEmpty()) conn.send("models");
	}

	public void prompt(String text) {
		add(Kind.USER, text);
		conn.send("prompt", "text", text);
	}

	/**
	 * Starts fresh, optionally in another project and/or a new git worktree. The prompt waits for the server's
	 * "new session" reply, which clears the conversation and arrives after any folder change.
	 */
	public void newSession(String dir, boolean worktree, String thenPrompt) {
		resetTranscript();
		promptAfterNew = thenPrompt;
		conn.send("new_session", "cwd", dir, "worktree", worktree);
	}

	public void resume(String id, String dir) {
		conn.send("resume", "sessionId", id, "cwd", dir);
	}

	public void interrupt() {
		conn.send("interrupt");
	}

	public void setMode(String value) {
		conn.send("set_mode", "mode", value);
	}

	public void setModel(String value) {
		conn.send("set_model", "model", value);
	}

	public void listDir(String path) {
		conn.send("ls", "path", path);
	}

	public void answer(Pending p, boolean allow, String planMode, Map<String, String> answers) {
		conn.send("permission_response", "id", p.id, "allow", allow, "mode", planMode, "answers", answers);
		pending.remove(p);
		add(Kind.INFO, answers != null ? "answered" : allow ? "allowed" : "denied");
	}

	// ---------- connection

	void onConnected() {
		connected = true;
		problem = "";
		refreshHome();
	}

	void onDisconnected(String why) {
		connected = false;
		running = false;
		pending.clear();
		problem = why;
	}

	// ---------- incoming

	void apply(JsonObject m) {
		switch (str(m, "type")) {
			case "hello" -> {
				homeDir = str(m, "home");
				apiKeyPresent = m.has("apiKeyPresent") && m.get("apiKeyPresent").getAsBoolean();
			}
			case "cwd" -> {
				cwd = str(m, "cwd");
				sessionId = optStr(m, "sessionId");
				resetTranscript();
			}
			case "session" -> {
				String id = optStr(m, "sessionId");
				sessionId = id;
				if (id != null) return;
				resetTranscript();
				if (promptAfterNew != null) {
					String text = promptAfterNew;
					promptAfterNew = null;
					prompt(text);
				}
			}
			case "mode" -> mode = str(m, "mode");
			case "model" -> model = str(m, "model");
			case "running" -> {
				running = m.get("running").getAsBoolean();
				if (!running) {
					pending.clear();
					for (Entry e : tools.values()) if (e.state == 0) e.state = 3;
				}
			}
			case "error" -> {
				promptAfterNew = null;
				add(Kind.ERROR, str(m, "message"));
			}
			case "history" -> loadHistory(m.getAsJsonArray("messages"));
			case "permission_request" -> permission(m);
			case "permission_cancel" -> pending.removeIf(p -> p.id.equals(str(m, "id")));
			case "home" -> {
				List<Recent> list = new ArrayList<>();
				for (JsonElement el : m.getAsJsonArray("sessions")) {
					JsonObject s = el.getAsJsonObject();
					list.add(new Recent(str(s, "id"), str(s, "title"), str(s, "cwd"), str(s, "project"), optStr(s, "repo"), optStr(s, "branch"), s.get("updatedAt").getAsLong()));
				}
				List<Project> projectList = new ArrayList<>();
				for (JsonElement el : m.getAsJsonArray("projects")) {
					JsonObject p = el.getAsJsonObject();
					projectList.add(new Project(str(p, "path"), str(p, "name"), optStr(p, "repo"), optStr(p, "branch"), p.get("sessions").getAsInt(), p.get("updatedAt").getAsLong()));
				}
				recent = list;
				projects = projectList;
				homeLoaded = true;
			}
			case "prs" -> {
				List<Pr> list = new ArrayList<>();
				for (JsonElement el : m.getAsJsonArray("prs")) {
					JsonObject p = el.getAsJsonObject();
					list.add(new Pr(p.get("number").getAsInt(), str(p, "title"), str(p, "repo"), str(p, "url"), p.get("draft").getAsBoolean(), p.get("updatedAt").getAsLong()));
				}
				prs = list;
				prsError = optStr(m, "error");
				prsLoaded = true;
			}
			case "models" -> {
				List<ModelOption> list = new ArrayList<>();
				for (JsonElement el : m.getAsJsonArray("models")) {
					JsonObject o = el.getAsJsonObject();
					list.add(new ModelOption(str(o, "value"), str(o, "displayName"), str(o, "description")));
				}
				models = list;
				model = str(m, "current");
			}
			case "ls" -> {
				List<FileEntry> list = new ArrayList<>();
				for (JsonElement el : m.getAsJsonArray("entries")) {
					JsonObject f = el.getAsJsonObject();
					list.add(new FileEntry(str(f, "name"), f.get("dir").getAsBoolean()));
				}
				lsPath = str(m, "path");
				lsEntries = list;
			}
			case "sdk" -> onSdk(m.getAsJsonObject("msg"));
			default -> {}
		}
	}

	private void onSdk(JsonObject m) {
		boolean sub = m.has("parent_tool_use_id") && !m.get("parent_tool_use_id").isJsonNull();
		switch (str(m, "type")) {
			case "system" -> {
				if ("init".equals(str(m, "subtype"))) modelName = str(m, "model");
				if ("compact_boundary".equals(str(m, "subtype"))) add(Kind.INFO, "context compacted");
			}
			case "stream_event" -> {
				if (!sub) onStream(m.getAsJsonObject("event"));
			}
			case "assistant" -> {
				JsonObject msg = m.getAsJsonObject("message");
				String id = str(msg, "id");
				for (JsonElement el : arr(msg, "content")) {
					JsonObject b = el.getAsJsonObject();
					String type = str(b, "type");
					if (type.endsWith("tool_use")) {
						Entry e = tool(str(b, "id"), str(b, "name"), sub);
						if (b.has("input")) setInput(e, b.getAsJsonObject("input"));
					} else if (type.equals("text") && !sub && !streamed.contains(id)) {
						add(Kind.TEXT, str(b, "text"));
						lastText = str(b, "text");
					}
				}
				if (m.has("error") && !m.get("error").isJsonNull()) add(Kind.ERROR, "error: " + m.get("error").getAsString());
			}
			case "user" -> {
				JsonObject msg = m.getAsJsonObject("message");
				for (JsonElement el : arr(msg, "content")) {
					JsonObject b = el.getAsJsonObject();
					if (!"tool_result".equals(str(b, "type"))) continue;
					Entry e = tools.get(str(b, "tool_use_id"));
					if (e == null) continue;
					e.state = b.has("is_error") && b.get("is_error").getAsBoolean() ? 2 : 1;
					e.output = resultText(b.get("content"));
				}
			}
			case "result" -> {
				if (m.has("total_cost_usd")) cost = m.get("total_cost_usd").getAsDouble();
				boolean ok = "success".equals(str(m, "subtype"));
				String line = (ok ? "✓ done" : "✗ " + str(m, "subtype").replace('_', ' '))
					+ (m.has("duration_ms") ? String.format(" · %.1fs", m.get("duration_ms").getAsDouble() / 1000) : "")
					+ (m.has("num_turns") ? " · " + m.get("num_turns").getAsInt() + " turns" : "");
				add(m.has("is_error") && m.get("is_error").getAsBoolean() ? Kind.ERROR : Kind.INFO, line);
				blocks.clear();
				for (Entry e : tools.values()) if (e.state == 0) e.state = 3;
				if (!screenOpen()) toast("Claude finished", clip(firstLine(lastText.isEmpty() ? line : lastText), 60));
			}
			default -> {}
		}
	}

	private void onStream(JsonObject ev) {
		String type = str(ev, "type");
		if (type.equals("message_start")) {
			currentMessage = str(ev.getAsJsonObject("message"), "id");
			streamed.add(currentMessage);
			return;
		}
		String key = currentMessage + ":" + (ev.has("index") ? ev.get("index").getAsInt() : -1);
		switch (type) {
			case "content_block_start" -> {
				JsonObject cb = ev.getAsJsonObject("content_block");
				String t = str(cb, "type");
				if (t.equals("text")) blocks.put(key, add(Kind.TEXT, str(cb, "text")));
				else if (t.endsWith("tool_use")) blocks.put(key, tool(str(cb, "id"), str(cb, "name"), false));
			}
			case "content_block_delta" -> {
				Entry e = blocks.get(key);
				JsonObject d = ev.getAsJsonObject("delta");
				if (e == null) return;
				if ("text_delta".equals(str(d, "type"))) e.text.append(str(d, "text"));
				else if ("input_json_delta".equals(str(d, "type"))) e.rawInput.append(str(d, "partial_json"));
			}
			case "content_block_stop" -> {
				Entry e = blocks.remove(key);
				if (e == null) return;
				if (e.kind == Kind.TEXT) lastText = e.text.toString();
				else if (e.rawInput.length() > 0) {
					try {
						setInput(e, JsonParser.parseString(e.rawInput.toString()).getAsJsonObject());
					} catch (RuntimeException ignored) {
						// the full assistant message carries the input too
					}
				}
			}
			default -> {}
		}
	}

	private void permission(JsonObject m) {
		Pending p = new Pending();
		p.id = str(m, "id");
		p.toolName = str(m, "toolName");
		JsonObject input = m.has("input") && m.get("input").isJsonObject() ? m.getAsJsonObject("input") : new JsonObject();
		p.what = m.has("title") && !m.get("title").isJsonNull() ? str(m, "title") : p.toolName + " " + describe(input);
		p.detail = detail(p.toolName, input);
		if (p.toolName.equals("ExitPlanMode") && input.has("plan")) p.plan = str(input, "plan");
		if (p.toolName.equals("AskUserQuestion") && input.has("questions")) {
			p.questions = new ArrayList<>();
			for (JsonElement el : input.getAsJsonArray("questions")) {
				JsonObject q = el.getAsJsonObject();
				List<String> labels = new ArrayList<>(), descriptions = new ArrayList<>();
				for (JsonElement o : arr(q, "options")) {
					labels.add(str(o.getAsJsonObject(), "label"));
					descriptions.add(str(o.getAsJsonObject(), "description"));
				}
				p.questions.add(new Question(str(q, "question"), labels, descriptions));
			}
		}
		pending.add(p);
		if (!screenOpen()) toast("Claude needs your OK", clip(p.what, 40) + " (press K)");
	}

	private void loadHistory(JsonArray messages) {
		resetTranscript();
		int start = Math.max(0, messages.size() - 300);
		for (int i = start; i < messages.size(); i++) {
			JsonObject m = messages.get(i).getAsJsonObject();
			boolean sub = m.has("parent_tool_use_id") && !m.get("parent_tool_use_id").isJsonNull();
			JsonObject msg = m.has("message") && m.get("message").isJsonObject() ? m.getAsJsonObject("message") : null;
			if (msg == null) continue;
			JsonElement content = msg.get("content");
			if ("user".equals(str(m, "type")) && !sub) {
				if (content != null && content.isJsonPrimitive()) addUserHistory(content.getAsString());
				for (JsonElement el : content != null && content.isJsonArray() ? content.getAsJsonArray() : new JsonArray()) {
					JsonObject b = el.getAsJsonObject();
					if ("text".equals(str(b, "type"))) addUserHistory(str(b, "text"));
				}
			}
			if ("assistant".equals(str(m, "type")) || "user".equals(str(m, "type"))) {
				JsonObject wrapper = new JsonObject();
				wrapper.addProperty("type", str(m, "type"));
				wrapper.add("message", msg);
				if (sub) wrapper.addProperty("parent_tool_use_id", "sub");
				else wrapper.add("parent_tool_use_id", com.google.gson.JsonNull.INSTANCE);
				onSdk(wrapper);
			}
		}
		for (Entry e : tools.values()) if (e.state == 0) e.state = 3;
		add(Kind.INFO, "resumed: new messages continue this session");
	}

	private void addUserHistory(String text) {
		if (!text.isBlank() && !text.stripLeading().matches("(?s)^<[a-z-]+>.*")) add(Kind.USER, text.strip());
	}

	// ---------- transcript helpers

	private Entry add(Kind kind, String text) {
		Entry e = new Entry(kind, text);
		transcript.add(e);
		if (transcript.size() > 600) transcript.subList(0, transcript.size() - 600).clear();
		return e;
	}

	private Entry tool(String id, String name, boolean sub) {
		Entry e = tools.get(id);
		if (e != null) return e;
		e = add(Kind.TOOL, "");
		e.toolId = id;
		e.toolName = name;
		e.sub = sub;
		tools.put(id, e);
		return e;
	}

	private void setInput(Entry e, JsonObject input) {
		e.text.setLength(0);
		e.text.append(describe(input));
		e.detail = detail(e.toolName, input);
	}

	private void resetTranscript() {
		transcript.clear();
		tools.clear();
		blocks.clear();
		streamed.clear();
		cost = null;
	}

	String describe(JsonObject input) {
		for (String k : new String[] {"command", "file_path", "notebook_path", "pattern", "url", "query", "description", "skill", "prompt"}) {
			if (input.has(k) && input.get(k).isJsonPrimitive()) {
				String v = firstLine(input.get(k).getAsString());
				if (!cwd.isEmpty() && v.startsWith(cwd + "/")) v = v.substring(cwd.length() + 1);
				return clip(v, 90);
			}
		}
		return "";
	}

	private static String detail(String tool, JsonObject input) {
		if (tool.equals("Edit") && input.has("old_string")) {
			return clip(str(input, "file_path") + "\n- " + str(input, "old_string") + "\n+ " + str(input, "new_string"), 700);
		}
		if (tool.equals("Bash") && input.has("command")) return clip("$ " + str(input, "command"), 700);
		return clip(new GsonBuilder().setPrettyPrinting().create().toJson(input), 700);
	}

	private static String resultText(JsonElement content) {
		if (content == null || content.isJsonNull()) return "";
		if (content.isJsonPrimitive()) return content.getAsString();
		if (content.isJsonArray()) {
			StringBuilder sb = new StringBuilder();
			for (JsonElement el : content.getAsJsonArray()) {
				JsonObject b = el.getAsJsonObject();
				sb.append("text".equals(str(b, "type")) ? str(b, "text") : "[" + str(b, "type") + "]").append('\n');
			}
			return sb.toString().strip();
		}
		return content.toString();
	}

	// ---------- misc

	private static boolean screenOpen() {
		return Minecraft.getInstance().screen instanceof ClaudeScreen;
	}

	private static void toast(String title, String body) {
		Minecraft mc = Minecraft.getInstance();
		SystemToast.add(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(title), Component.literal(body));
	}

	static String str(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e == null || e.isJsonNull() ? "" : e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	static String optStr(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static JsonArray arr(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
	}

	static String firstLine(String s) {
		int i = s.indexOf('\n');
		return i < 0 ? s : s.substring(0, i);
	}

	static String clip(String s, int n) {
		return s.length() > n ? s.substring(0, n - 1) + "…" : s;
	}
}
