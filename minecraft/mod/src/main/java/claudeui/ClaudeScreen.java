package claudeui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/** The Claude Code screen: tabs, sidebar, Home / Session / Projects / Pull Requests / Sessions / Settings, and a prompt bar. */
public final class ClaudeScreen extends Screen {
	public enum Section { HOME, SESSION, PROJECTS, PRS, SESSIONS, SETTINGS }

	// ARGB colors: a vanilla-style light frame around dark panes.
	static final int BLACK = 0xFF000000, PANEL = 0xFFC6C6C6, HI = 0xFFFFFFFF, SHADOW = 0xFF555555, LABEL = 0xFF3F3F3F;
	static final int PANE = 0xFF1B1B1B, CARD = 0xFF2A2A2A, CARD_HOVER = 0xFF353535, CARD_EDGE = 0xFF484848;
	static final int SELECT_BG = 0xFF1E2C42, SELECT_EDGE = 0xFF5C86C9;
	static final int TEXT = 0xFFFFFFFF, DIM = 0xFFB4B4B4, FAINT = 0xFF808080;
	static final int ORANGE = 0xFFD97757, YELLOW = 0xFFF5C542, GREEN = 0xFF6CCB7E, RED = 0xFFEF6B6B, AQUA = 0xFF8ED8E0;

	private static final Section[] TABS = {Section.HOME, Section.PROJECTS, Section.SESSIONS, Section.PRS, Section.SETTINGS, Section.SESSION};
	private static final String[][] MODES = {
		{"default", "ask", "Ask before edits and commands"},
		{"acceptEdits", "accept edits", "Edits go through; commands still ask"},
		{"plan", "plan", "Read and plan only, no changes"},
	};
	private static final Item[] BLOCKS = {
		Items.GRASS_BLOCK, Items.CRAFTING_TABLE, Items.COBBLESTONE, Items.OAK_LOG, Items.BRICKS, Items.PUMPKIN,
		Items.HAY_BLOCK, Items.MOSSY_COBBLESTONE, Items.TNT, Items.SANDSTONE, Items.MELON, Items.BOOKSHELF,
		Items.COPPER_BLOCK, Items.AMETHYST_BLOCK, Items.PRISMARINE, Items.HONEYCOMB_BLOCK,
	};

	// Remembered while the game runs, so reopening returns to where you were.
	private static Section section = Section.HOME;
	private static boolean worktree;
	private static boolean attachOpen;
	private static String draft = "";
	private static boolean followBottom = true;
	private static double attachScroll;
	private static final Map<Section, Double> scroll = new EnumMap<>(Section.class);
	private static final Map<String, Boolean> requested = new HashMap<>();
	static ClaudeScreen current;

	private final Model m = ClaudeUIClient.CONNECTION.model;
	private final List<Hit> hits = new ArrayList<>();
	private EditBox input;
	private int px, py, pw, ph, sx, sy, sw, sh, cx, cy, cw, ch, ix, iy, iw, ih, by;
	private int contentHeight;
	private int seenPending;

	private record Hit(int x, int y, int w, int h, Runnable action, List<Component> tooltip) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/** True for the copy drawn on Claude Screen walls: no clicks, sounds or focus. */
	private final boolean offscreen;

	public ClaudeScreen() {
		this(false);
	}

	ClaudeScreen(boolean offscreen) {
		super(Component.literal("Claude Code"));
		this.offscreen = offscreen;
	}

	/** Opens the file picker (also used by the self-test). */
	public static void openAttach() {
		attachOpen = true;
		attachScroll = 0;
		ClaudeUIClient.CONNECTION.model.listDir("");
	}

	public static void select(Section s) {
		section = s;
		attachOpen = false;
		if (s == Section.SESSION) followBottom = true;
		if (current != null) current.load();
	}

	@Override
	protected void init() {
		if (!offscreen) current = this;
		int tabH = 24;
		pw = Mth.clamp(width - 20, 300, 620);
		ph = Mth.clamp(height - tabH - 12, 170, 380);
		px = (width - pw) / 2;
		py = (height - ph - tabH) / 2 + tabH;
		by = py + ph - 7 - 16;
		ih = 18;
		iy = by - 4 - ih;
		ix = px + 6;
		iw = pw - 12;
		sx = px + 6;
		sy = py + 26;
		sw = Mth.clamp(pw / 4, 104, 140);
		sh = iy - 5 - sy;
		cx = sx + sw + 4;
		cy = sy;
		cw = px + pw - 6 - cx;
		ch = sh;

		input = new EditBox(font, ix + 22, iy + 5, iw - 28, ih - 6, Component.literal("Prompt"));
		input.setBordered(false);
		input.setMaxLength(4000);
		input.setTextColor(TEXT);
		input.setValue(draft);
		input.setResponder(s -> draft = s);
		addRenderableWidget(input);
		if (!offscreen) setInitialFocus(input);
		ClaudeUIClient.CONNECTION.connectIfNeeded();
		requested.clear();
		load();
	}

	/** Asks the server for whatever the current section shows. Runs on open and on navigation, never on a timer. */
	private void load() {
		if (!m.connected) return;
		if (section != Section.SESSION && section != Section.SETTINGS && requested.putIfAbsent("home", true) == null) m.refreshHome();
	}

	@Override
	public void removed() {
		if (current == this) current = null;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------- input

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && input.isFocused()) {
			run();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.action() != null && hit.contains(event.x(), event.y())) {
				Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (mx >= cx && mx < cx + cw && my >= cy && my < cy + ch) {
			if (attachOpen) attachScroll -= scrollY * 18;
			else scroll.put(section, scroll.getOrDefault(section, 0.0) - scrollY * 18);
			if (section == Section.SESSION && !attachOpen && scrollY > 0) followBottom = false;
			return true;
		}
		return super.mouseScrolled(mx, my, scrollX, scrollY);
	}

	private void run() {
		String text = input.getValue().strip();
		if (text.isEmpty()) return;
		if (m.running) {
			m.prompt(text); // the server says it's busy; keeps the text visible in the session
			return;
		}
		input.setValue("");
		select(Section.SESSION);
		boolean inWorktree = m.cwd.contains("/.claude/worktrees/");
		if (m.sessionId == null && worktree && !inWorktree) m.newSession(null, true, text);
		else m.prompt(text);
	}

	// ---------- render

	@Override
	public void render(GuiGraphics gui, int mx, int my, float delta) {
		paint(new Canvas.Gui(gui, font), mx, my);
		super.render(gui, mx, my, delta);
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.tooltip() != null && !hit.tooltip().isEmpty() && hit.contains(mx, my)) {
				List<FormattedCharSequence> lines = new ArrayList<>();
				for (Component c : hit.tooltip()) lines.addAll(font.split(c, 260));
				gui.setTooltipForNextFrame(font, lines, mx, my);
				break;
			}
		}
	}

	/** Draws the whole UI; shared by this screen and by Claude Screen walls in the world (offscreen). */
	void paint(Canvas g, int mx, int my) {
		hits.clear();
		if (m.pending.size() > seenPending) {
			section = Section.SESSION; // Claude is waiting on you: show it
			followBottom = true;
		}
		seenPending = m.pending.size();

		tabs(g, mx, my);
		panel(g, px, py, pw, ph);
		header(g);
		sidebar(g, mx, my);
		pane(g, cx, cy, cw, ch);

		double max = Math.max(0, contentHeight - (ch - 8));
		boolean chat = section == Section.SESSION && !attachOpen;
		double offset = chat && followBottom ? max : Mth.clamp(attachOpen ? attachScroll : scroll.getOrDefault(section, 0.0), 0, max);
		if (attachOpen) attachScroll = offset;
		else scroll.put(section, offset);
		if (chat && offset >= max - 1) followBottom = true;
		int top = cy + 5 - (int) offset;
		g.clip(cx + 1, cy + 1, cx + cw - 1, cy + ch - 1);
		int end;
		if (!m.connected) end = offline(g, mx, my, top);
		else if (attachOpen) end = attach(g, mx, my, top);
		else end = switch (section) {
			case HOME -> home(g, mx, my, top);
			case SESSION -> session(g, mx, my, top);
			case PROJECTS -> projects(g, mx, my, top);
			case PRS -> pullRequests(g, mx, my, top, Integer.MAX_VALUE);
			case SESSIONS -> sessions(g, mx, my, top);
			case SETTINGS -> settings(g, mx, my, top);
		};
		if (m.connected && !attachOpen && section == Section.SESSION) sessionStatus(g, mx, my);
		g.unclip();
		contentHeight = end - top;
		if (max > 0) scrollbar(g, offset, max);

		promptBar(g);
		buttons(g, mx, my);
	}

	private void tabs(Canvas g, int mx, int my) {
		int tw = 26, gap = 2, total = TABS.length * (tw + gap) - gap;
		int x = px + (pw - total) / 2;
		for (Section s : TABS) {
			boolean on = s == section && !attachOpen;
			int y = on ? py - 24 : py - 21;
			int h = on ? 26 : 21;
			if (on) {
				panel(g, x, y, tw, h + 2);
				g.fill(x + 3, py - 1, x + tw - 3, py + 3, PANEL); // merge into the frame
			} else {
				bevel(g, x, y, tw, h, mx >= x && mx < x + tw && my >= y && my < y + h ? 0xFFB5B5B5 : 0xFF8B8B8B);
			}
			icon(g, s, x + 5, y + 4, 16);
			hit(x, y, tw, h, () -> select(s), List.of(Component.literal(label(s))));
			x += tw + gap;
		}
	}

	private void header(Canvas g) {
		g.scaledText("Claude Code", px + 9, py + 7, 1.5f, LABEL);

		Minecraft mc = Minecraft.getInstance();
		PlayerSkin skin = mc.player != null ? mc.player.getSkin() : DefaultPlayerSkin.get(mc.getUser().getProfileId());
		int fx = px + pw - 9 - 16, fy = py + 5;
		g.fill(fx - 1, fy - 1, fx + 17, fy + 17, BLACK);
		g.face(skin, fx, fy, 16);
		String welcome = "Welcome back, " + mc.getUser().getName();
		g.text(welcome, fx - 6 - font.width(welcome), py + 9, LABEL);
	}

	private void sidebar(Canvas g, int mx, int my) {
		pane(g, sx, sy, sw, sh);
		Object[][] items = {
			{Section.HOME, "Home"},
			{Section.SESSION, "Session"},
			{Section.PROJECTS, "Projects"},
			{Section.PRS, "Pull Requests"},
			{Section.SESSIONS, "Sessions"},
			{null, "New Session"},
			{Section.SETTINGS, "Settings"},
		};
		int rowH = Math.min(22, (sh - 4) / items.length);
		int y = sy + 2;
		for (Object[] item : items) {
			Section s = (Section) item[0];
			String name = (String) item[1];
			int x = sx + 2, w = sw - 4;
			boolean hover = mx >= x && mx < x + w && my >= y && my < y + rowH;
			if (s == section && !attachOpen) {
				g.fill(x, y, x + w, y + rowH, SELECT_EDGE);
				g.fill(x + 1, y + 1, x + w - 1, y + rowH - 1, SELECT_BG);
			} else if (hover) {
				g.fill(x, y, x + w, y + rowH, CARD_HOVER);
			}
			int iconY = y + (rowH - 12) / 2;
			if (s == null) g.text(">_", x + 4, y + (rowH - 8) / 2, TEXT);
			else icon(g, s, x + 4, iconY, 12);
			g.text(fit(name, w - 30), x + 22, y + (rowH - 8) / 2, TEXT);
			if (s == Section.SESSION && (m.running || !m.pending.isEmpty())) dot(g, x + w - 9, y + (rowH - 5) / 2, m.pending.isEmpty() ? GREEN : ORANGE);
			if (s == null) {
				hit(x, y, w, rowH, () -> {
					m.newSession(null, worktree, null);
					select(Section.SESSION);
				}, List.of(Component.literal(worktree ? "Start a new session in a fresh git worktree" : "Start a new session in " + project())));
			} else {
				hit(x, y, w, rowH, () -> select(s), null);
			}
			y += rowH;
			if (s != Section.SETTINGS) g.fill(x + 2, y - 1, x + w - 2, y, 0xFF2E2E2E);
		}
	}

	// ---------- sections

	private int home(Canvas g, int mx, int my, int y) {
		y = pendingBanner(g, mx, my, y);
		y = heading(g, "Recent Sessions", m.homeLoaded ? plural(m.recent.size(), "session") : "loading…", y);
		for (Model.Recent r : m.recent.subList(0, Math.min(3, m.recent.size()))) y = sessionCard(g, mx, my, r, y);
		if (m.homeLoaded && m.recent.isEmpty()) y = note(g, "No sessions yet. Describe a task below to start one.", y);
		y += 6;
		return pullRequests(g, mx, my, y, 4);
	}

	private int sessions(Canvas g, int mx, int my, int y) {
		y = heading(g, "Sessions", m.recent.size() + " across your projects", y);
		for (Model.Recent r : m.recent) y = sessionCard(g, mx, my, r, y);
		return y;
	}

	private int projects(Canvas g, int mx, int my, int y) {
		y = heading(g, "Projects", plural(m.projects.size(), "project"), y);
		for (Model.Project p : m.projects) {
			boolean here = p.path().equals(m.cwd);
			String sub = (p.repo() != null ? p.repo() : tilde(p.path())) + (p.branch() != null ? " · " + p.branch() : "");
			y = card(g, mx, my, y, blockFor(p.name()), here ? GREEN : YELLOW, p.name(), sub, plural(p.sessions(), "session"), here,
				() -> {
					m.newSession(p.path(), worktree, null);
					select(Section.SESSION);
				},
				List.of(Component.literal(p.path()), Component.literal(worktree ? "Click: new session in a fresh worktree" : "Click: new session here").withStyle(ChatFormatting.GRAY)));
		}
		return y;
	}

	private int pullRequests(Canvas g, int mx, int my, int y, int limit) {
		y = heading(g, "Pull Requests", m.prsLoaded ? plural(m.prs.size(), "pull request") : "loading…", y);
		if (m.prsError != null) return note(g, m.prsError, y);
		if (m.prsLoaded && m.prs.isEmpty()) y = note(g, "No open pull requests.", y);
		for (Model.Pr pr : m.prs.subList(0, Math.min(limit, m.prs.size()))) {
			Model.Project local = m.projects.stream().filter(p -> pr.repo().equals(p.repo())).findFirst().orElse(null);
			int top = y, bw = font.width("Review") + 10;
			y = card(g, mx, my, y, Items.FILLED_MAP, pr.draft() ? FAINT : GREEN, pr.title(), "#" + pr.number() + " · " + pr.repo(), ago(pr.updatedAt()), local != null ? bw + 6 : 0, false,
				() -> ConfirmLinkScreen.confirmLinkNow(this, pr.url()),
				List.of(Component.literal(pr.title()), Component.literal(pr.url()).withStyle(ChatFormatting.GRAY), Component.literal("Click to open in your browser").withStyle(ChatFormatting.GRAY)));
			if (local != null) {
				int bx = cx + cw - 22 - bw - font.width(ago(pr.updatedAt())), byy = top + 7;
				lightButton(g, mx, my, bx, byy, bw, 13, "Review", () -> {
					m.newSession(local.path(), worktree, "Review pull request #" + pr.number() + " (" + pr.url() + "): summarize what it changes, flag risks and bugs, and suggest fixes. Use gh to read it.");
					select(Section.SESSION);
				}, List.of(Component.literal("Ask Claude to review this PR in " + local.name())));
			}
		}
		return y;
	}

	/** Project, mode, model and cost, pinned above the scrolling conversation. */
	private void sessionStatus(Canvas g, int mx, int my) {
		g.fill(cx + 1, cy + 1, cx + cw - 1, cy + 17, PANE);
		g.fill(cx + 1, cy + 17, cx + cw - 1, cy + 18, 0xFF2E2E2E);
		String info = project() + "  ·  mode " + modeLabel(m.mode) + "  ·  " + modelLabel() + (m.cost != null ? String.format("  ·  $%.3f", m.cost) : "");
		g.text(fit(info, cw - (m.running ? 60 : 14)), cx + 6, cy + 5, FAINT);
		if (m.running) lightButton(g, mx, my, cx + cw - 50, cy + 2, 42, 14, "Stop", m::interrupt, List.of(Component.literal("Interrupt the current run")));
	}

	private int session(Canvas g, int mx, int my, int y) {
		y += 16;
		int width = cw - 16;
		if (m.transcript.isEmpty() && m.pending.isEmpty()) {
			return note(g, (m.sessionId == null ? "New session in " + project() : "Session " + m.sessionId.substring(0, 8)) + ". Describe a task below and press Run.", y);
		}
		for (Model.Entry e : m.transcript) y = entry(g, mx, my, e, cx + 6, y, width);
		if (m.running && m.pending.isEmpty()) {
			String dots = ".".repeat((int) (Util.getMillis() / 400 % 4));
			g.text("Claude is working" + dots, cx + 6, y + 2, YELLOW);
			y += 14;
		}
		for (Model.Pending p : new ArrayList<>(m.pending)) y = pendingCard(g, mx, my, p, y);
		return y;
	}

	private int settings(Canvas g, int mx, int my, int y) {
		y = heading(g, "Permission mode", "", y);
		for (String[] mode : MODES) {
			y = option(g, mx, my, y, mode[1], mode[2], mode[0].equals(m.mode), () -> m.setMode(mode[0]));
		}
		y += 4;
		y = heading(g, "Model", modelLabel(), y);
		if (m.models.isEmpty()) y = note(g, "Loading models…", y);
		for (Model.ModelOption o : m.models) {
			y = option(g, mx, my, y, o.name(), o.description(), o.value().equals(m.model), () -> m.setModel(o.value()));
		}
		y += 4;
		y = heading(g, "New sessions", "", y);
		y = option(g, mx, my, y, "Use a git worktree", "New sessions get their own branch under .claude/worktrees", worktree, () -> worktree = !worktree);
		y += 4;
		y = heading(g, "Connection", "", y);
		return note(g, "claude-ui on 127.0.0.1:" + Connection.PORT + (m.connected ? " · connected" : " · offline") + " · working in " + tilde(m.cwd), y);
	}

	private int offline(Canvas g, int mx, int my, int y) {
		y = heading(g, ClaudeUIClient.CONNECTION.connecting() ? "Connecting…" : "Not connected", "", y);
		y = note(g, (m.problem.isEmpty() ? "Looking for claude-ui on 127.0.0.1:" + Connection.PORT + "." : m.problem)
			+ " It runs on this Mac: open the Claude UI app, or run npm start in the claude-ui folder, then press Retry.", y);
		lightButton(g, mx, my, cx + 6, y + 2, 50, 16, "Retry", () -> ClaudeUIClient.CONNECTION.connectIfNeeded(), null);
		lightButton(g, mx, my, cx + 62, y + 2, 120, 16, "Open Claude UI app", ClaudeScreen::openApp, List.of(Component.literal("Starts the server; press Retry once it's up")));
		return y + 22;
	}

	private int attach(Canvas g, int mx, int my, int y) {
		String where = m.lsPath == null ? "" : m.lsPath;
		y = heading(g, "Attach a file", "/" + where, y);
		lightButton(g, mx, my, cx + cw - 48, y - 15, 40, 13, "Close", () -> attachOpen = false, null);
		y = note(g, "Adds it to your message as @path; Claude reads it when you Run.", y);
		if (!where.isEmpty()) {
			attachScrollReset(where);
			String up = where.contains("/") ? where.substring(0, where.lastIndexOf('/')) : "";
			y = card(g, mx, my, y, Items.ARROW, FAINT, "..", "up one folder", "", false, () -> m.listDir(up), null);
		}
		for (Model.FileEntry f : m.lsEntries) {
			String path = where.isEmpty() ? f.name() : where + "/" + f.name();
			y = card(g, mx, my, y, f.dir() ? Items.CHEST : Items.PAPER, f.dir() ? YELLOW : FAINT, f.name() + (f.dir() ? "/" : ""), f.dir() ? "folder" : where.isEmpty() ? "in the project root" : "in " + where, "", false,
				() -> {
					if (f.dir()) m.listDir(path);
					else {
						String v = input.getValue();
						input.setValue((v.isEmpty() || v.endsWith(" ") ? v : v + " ") + "@" + path + " ");
						attachOpen = false;
					}
				}, null);
		}
		return y;
	}

	private static String attachShown = "";

	private static void attachScrollReset(String where) {
		if (!where.equals(attachShown)) attachScroll = 0;
		attachShown = where;
	}

	// ---------- pieces

	private int sessionCard(Canvas g, int mx, int my, Model.Recent r, int y) {
		boolean here = r.id().equals(m.sessionId);
		String sub = r.project() + (r.branch() != null ? " · " + r.branch() : "") + (r.repo() != null ? " · " + r.repo() : "");
		return card(g, mx, my, y, blockFor(r.project()), here ? (m.running ? GREEN : TEXT) : YELLOW, r.title(), sub, ago(r.updatedAt()), here,
			() -> {
				if (!here) m.resume(r.id(), r.cwd());
				select(Section.SESSION);
			},
			List.of(Component.literal(r.title()), Component.literal(tilde(r.cwd())).withStyle(ChatFormatting.GRAY), Component.literal(here ? "Current session" : "Click to resume").withStyle(ChatFormatting.GRAY)));
	}

	private int card(Canvas g, int mx, int my, int y, Item icon, int dotColor, String title, String sub, String right, boolean selected, Runnable action, List<Component> tooltip) {
		return card(g, mx, my, y, icon, dotColor, title, sub, right, 0, selected, action, tooltip);
	}

	/** A session-style row: icon, status dot, title and subtitle, a right-hand note, and `reserve` px kept free for a button. */
	private int card(Canvas g, int mx, int my, int y, Item icon, int dotColor, String title, String sub, String right, int reserve, boolean selected, Runnable action, List<Component> tooltip) {
		int x = cx + 4, w = cw - 8, h = 26;
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + h && my >= cy && my < cy + ch;
		g.fill(x, y, x + w, y + h, selected ? SELECT_EDGE : CARD_EDGE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, selected ? SELECT_BG : hover ? CARD_HOVER : CARD);
		g.item(new ItemStack(icon), x + 5, y + 5, 16);
		dot(g, x + 27, y + 6, dotColor);
		int rightW = (right.isEmpty() ? 0 : font.width(right) + 8) + reserve;
		g.text(fit(title, w - 50 - rightW), x + 36, y + 4, TEXT);
		g.text(fit(sub, w - 50 - rightW), x + 36, y + 15, DIM);
		if (!right.isEmpty()) g.text(right, x + w - 14 - font.width(right), y + 9, DIM);
		g.text(">", x + w - 9, y + 9, hover ? TEXT : FAINT);
		hit(x, y, w, h, action, tooltip);
		return y + h + 3;
	}

	private int option(Canvas g, int mx, int my, int y, String title, String sub, boolean on, Runnable action) {
		int x = cx + 4, w = cw - 8, h = 22;
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
		g.fill(x, y, x + w, y + h, on ? SELECT_EDGE : CARD_EDGE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, on ? SELECT_BG : hover ? CARD_HOVER : CARD);
		g.fill(x + 6, y + 7, x + 14, y + 15, on ? 0xFF9BC1FF : 0xFF5A5A5A);
		if (!on) g.fill(x + 7, y + 8, x + 13, y + 14, CARD);
		g.text(fit(title, w - 30), x + 20, y + 3, TEXT);
		g.text(fit(sub, w - 30), x + 20, y + 12, FAINT);
		hit(x, y, w, h, action, null);
		return y + h + 2;
	}

	private int entry(Canvas g, int mx, int my, Model.Entry e, int x, int y, int width) {
		List<FormattedCharSequence> lines = lines(e, width - (e.kind == Model.Kind.USER ? 8 : 0));
		switch (e.kind) {
			case USER -> {
				int h = lines.size() * 10 + 6;
				g.fill(x - 2, y, x + width, y + h, 0xFF262626);
				g.fill(x - 2, y, x, y + h, ORANGE);
				for (int i = 0; i < lines.size(); i++) g.text(lines.get(i), x + 5, y + 3 + i * 10, TEXT);
				return y + h + 5;
			}
			case TOOL -> {
				int color = switch (e.state) { case 0 -> YELLOW; case 1 -> GREEN; case 2 -> RED; default -> FAINT; };
				int indent = e.sub ? 10 : 0;
				dot(g, x + indent, y + 2, color);
				String name = e.toolName;
				g.text(name, x + indent + 9, y, TEXT);
				g.text(fit(e.text.toString(), width - indent - 16 - font.width(name)), x + indent + 13 + font.width(name), y, DIM);
				List<Component> tip = new ArrayList<>();
				for (String l : e.detail.split("\n")) tip.add(Component.literal(l));
				if (!e.output.isEmpty()) tip.add(Component.literal(e.expanded ? "Click to collapse" : "Click to see the output").withStyle(ChatFormatting.GRAY));
				hit(x, y - 1, width, 10, e.output.isEmpty() ? null : () -> e.expanded = !e.expanded, tip);
				y += 11;
				for (FormattedCharSequence l : lines) {
					g.text(l, x + indent + 9, y, e.state == 2 ? RED : FAINT);
					y += 10;
				}
				return y + 1;
			}
			default -> {
				int color = e.kind == Model.Kind.ERROR ? RED : e.kind == Model.Kind.INFO ? FAINT : TEXT;
				for (FormattedCharSequence l : lines) {
					g.text(l, x, y, color);
					y += 10;
				}
				return y + 4;
			}
		}
	}

	private List<FormattedCharSequence> lines(Model.Entry e, int width) {
		if (e.cacheWidth == width && e.cacheLength == e.text.length() + e.output.length() && e.cacheState == e.state && e.cacheExpanded == e.expanded) return e.lines;
		List<FormattedCharSequence> out = new ArrayList<>();
		switch (e.kind) {
			case TEXT -> {
				for (Component c : markdown(e.text.toString())) out.addAll(c.getString().isEmpty() ? List.of(FormattedCharSequence.EMPTY) : font.split(c, width));
			}
			case TOOL -> {
				if (e.expanded) {
					String[] outLines = e.output.split("\n");
					for (int i = 0; i < Math.min(14, outLines.length); i++) out.addAll(font.split(Component.literal(outLines[i]), width - 12));
					if (outLines.length > 14) out.add(Component.literal("… " + (outLines.length - 14) + " more lines").getVisualOrderText());
				}
			}
			default -> out.addAll(font.split(Component.literal(e.text.toString()), width));
		}
		e.lines = out;
		e.cacheWidth = width;
		e.cacheLength = e.text.length() + e.output.length();
		e.cacheState = e.state;
		e.cacheExpanded = e.expanded;
		return out;
	}

	/** Markdown to plain, readable lines: inline markup stripped, headings bold, code tinted. */
	static List<Component> markdown(String md) {
		List<Component> out = new ArrayList<>();
		boolean code = false;
		for (String raw : md.split("\n", -1)) {
			if (raw.strip().startsWith("```")) {
				code = !code;
				continue;
			}
			if (code) {
				out.add(Component.literal("  " + raw).withStyle(s -> s.withColor(AQUA & 0xFFFFFF)));
				continue;
			}
			if (raw.matches("^\\s*\\|?\\s*:?-{3,}.*")) continue;
			boolean heading = raw.matches("^#{1,6}\\s.*");
			String line = raw.replaceFirst("^#{1,6}\\s+", "")
				.replaceFirst("^(\\s*)[-*]\\s+", "$1• ")
				.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
				.replaceAll("`([^`]+)`", "$1")
				.replaceAll("\\[([^\\]]+)]\\([^)]+\\)", "$1");
			if (line.strip().startsWith("|")) line = String.join("  ·  ", List.of(line.strip().replaceAll("^\\||\\|$", "").split("\\|"))).replaceAll("\\s+·\\s+", "  ·  ");
			if (line.isBlank()) {
				if (!out.isEmpty() && !out.get(out.size() - 1).getString().isEmpty()) out.add(Component.empty());
				continue;
			}
			MutableComponent c = Component.literal(line);
			out.add(heading ? c.withStyle(ChatFormatting.BOLD) : c);
		}
		while (!out.isEmpty() && out.get(out.size() - 1).getString().isEmpty()) out.remove(out.size() - 1);
		return out;
	}

	private int pendingBanner(Canvas g, int mx, int my, int y) {
		if (m.pending.isEmpty()) return y;
		Model.Pending p = m.pending.get(0);
		return card(g, mx, my, y, Items.BELL, ORANGE, "Claude needs your OK", p.what, "open", false, () -> select(Section.SESSION), null);
	}

	private int pendingCard(Canvas g, int mx, int my, Model.Pending p, int y) {
		int x = cx + 4, w = cw - 8;
		List<FormattedCharSequence> body = new ArrayList<>();
		String title;
		if (p.questions != null) title = "Claude has a question";
		else if (p.plan != null) title = "Plan ready: approve to start";
		else title = "Claude wants to: " + p.what;
		if (p.plan != null) for (Component c : markdown(p.plan)) body.addAll(font.split(c, w - 12));
		else if (p.questions == null) for (String l : p.detail.split("\n")) body.addAll(font.split(Component.literal(l), w - 12));
		if (body.size() > 16) body = new ArrayList<>(body.subList(0, 16));
		int questionRows = p.questions == null ? 0 : p.questions.stream().mapToInt(q -> 1 + q.options().size()).sum();
		int h = 16 + body.size() * 10 + questionRows * 16 + 22;
		g.fill(x, y, x + w, y + h, ORANGE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF2B2219);
		g.text(fit(title, w - 12), x + 6, y + 5, YELLOW);
		int ly = y + 17;
		for (FormattedCharSequence l : body) {
			g.text(l, x + 6, ly, DIM);
			ly += 10;
		}
		if (p.questions != null) {
			Map<String, String> answers = p.answers;
			for (Model.Question q : p.questions) {
				g.text(fit(q.question(), w - 12), x + 6, ly + 2, TEXT);
				ly += 16;
				for (int i = 0; i < q.options().size(); i++) {
					String label = q.options().get(i);
					String desc = q.descriptions().get(i);
					lightButton(g, mx, my, x + 10, ly - 1, Math.min(w - 20, font.width(label) + 16), 14, (label.equals(answers.get(q.question())) ? "✔ " : "") + label, () -> {
						answers.put(q.question(), label);
						if (answers.size() == p.questions.size()) m.answer(p, true, null, Map.copyOf(answers));
					}, desc.isEmpty() ? null : List.of(Component.literal(desc)));
					ly += 16;
				}
			}
			lightButton(g, mx, my, x + 6, ly + 2, 40, 14, "Skip", () -> m.answer(p, false, null, null), null);
		} else if (p.plan != null) {
			int bx = x + 6;
			bx += lightButton(g, mx, my, bx, ly + 2, 60, 14, "Approve", () -> m.answer(p, true, "default", null), List.of(Component.literal("Start, asking before edits"))) + 4;
			bx += lightButton(g, mx, my, bx, ly + 2, 130, 14, "Approve + accept edits", () -> m.answer(p, true, "acceptEdits", null), null) + 4;
			lightButton(g, mx, my, bx, ly + 2, 86, 14, "Keep planning", () -> m.answer(p, false, null, null), null);
		} else {
			lightButton(g, mx, my, x + 6, ly + 2, 50, 14, "Allow", () -> m.answer(p, true, null, null), List.of(Component.literal("Allow this once")));
			lightButton(g, mx, my, x + 60, ly + 2, 50, 14, "Deny", () -> m.answer(p, false, null, null), null);
		}
		return y + h + 4;
	}

	private static String plural(int n, String word) {
		return n + " " + word + (n == 1 ? "" : "s");
	}

	private void promptBar(Canvas g) {
		g.fill(ix, iy, ix + iw, iy + ih, BLACK);
		g.fill(ix + 1, iy + 1, ix + iw - 1, iy + ih - 1, 0xFF101010);
		g.fill(ix + 1, iy + 1, ix + iw - 1, iy + 2, 0xFF3A3A3A);
		g.text(">_", ix + 6, iy + 5, TEXT);
		g.fill(ix + 18, iy + 4, ix + 19, iy + ih - 4, 0xFF4A4A4A);
		if (offscreen && !draft.isEmpty()) g.text(fit(draft, iw - 30), ix + 24, iy + 5, TEXT);
		else if (input.getValue().isEmpty()) g.text(m.running ? "Claude is working… press Stop to interrupt" : "Describe a task or ask a question…", ix + 24, iy + 5, FAINT);
	}

	private void buttons(Canvas g, int mx, int my) {
		int x = ix;
		x += lightButton(g, mx, my, x, by, 54, 16, attachOpen ? "Attach ✔" : "Attach", () -> {
			if (attachOpen) attachOpen = false;
			else openAttach();
		}, List.of(Component.literal("Add a project file to your message"))) + 4;
		x += lightButton(g, mx, my, x, by, Math.max(70, font.width(modelLabel()) + 14), 16, modelLabel(), this::nextModel,
			List.of(Component.literal("Model: " + modelLabel()), Component.literal("Click to switch").withStyle(ChatFormatting.GRAY))) + 4;
		x += lightButton(g, mx, my, x, by, 64, 16, (worktree ? "✔ " : "") + "worktree", () -> worktree = !worktree,
			List.of(Component.literal(worktree ? "New sessions start in a fresh git worktree" : "New sessions use the project folder directly"))) + 4;
		lightButton(g, mx, my, x, by, 74, 16, "mode: " + modeLabel(m.mode), this::nextMode, List.of(Component.literal(modeHint(m.mode)), Component.literal("Click to switch").withStyle(ChatFormatting.GRAY)));
		int rw = 64;
		if (m.running) lightButton(g, mx, my, ix + iw - rw, by, rw, 16, "Stop ■", m::interrupt, List.of(Component.literal("Interrupt Claude")));
		else lightButton(g, mx, my, ix + iw - rw, by, rw, 16, "Run ▶", this::run, List.of(Component.literal("Send (Enter)")));
	}

	private void nextModel() {
		if (m.models.isEmpty()) return;
		int i = 0;
		for (int j = 0; j < m.models.size(); j++) if (m.models.get(j).value().equals(m.model)) i = j;
		m.setModel(m.models.get((i + 1) % m.models.size()).value());
	}

	private void nextMode() {
		int i = 0;
		for (int j = 0; j < MODES.length; j++) if (MODES[j][0].equals(m.mode)) i = j;
		m.setMode(MODES[(i + 1) % MODES.length][0]);
	}

	private int heading(Canvas g, String title, String right, int y) {
		g.text(title, cx + 6, y + 2, TEXT);
		if (!right.isEmpty()) g.text(right, cx + cw - 8 - font.width(right), y + 2, DIM);
		return y + 15;
	}

	private int note(Canvas g, String text, int y) {
		for (FormattedCharSequence l : font.split(Component.literal(text), cw - 16)) {
			g.text(l, cx + 6, y, DIM);
			y += 10;
		}
		return y + 4;
	}

	/** A vanilla-looking light button; returns its width. */
	private int lightButton(Canvas g, int mx, int my, int x, int y, int w, int h, String label, Runnable action, List<Component> tooltip) {
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
		bevel(g, x, y, w, h, hover ? 0xFFDADADA : PANEL);
		if (hover) g.outline(x, y, w, h, HI);
		g.text(fit(label, w - 6), x + (w - Math.min(w - 6, font.width(label))) / 2, y + (h - 8) / 2, LABEL);
		hit(x, y, w, h, action, tooltip);
		return w;
	}

	private void scrollbar(Canvas g, double offset, double max) {
		int trackH = ch - 4, thumbH = Math.max(12, (int) (trackH * (ch - 8) / (double) contentHeight));
		int ty = cy + 2 + (int) ((trackH - thumbH) * (offset / max));
		g.fill(cx + cw - 4, cy + 2, cx + cw - 2, cy + ch - 2, 0xFF262626);
		g.fill(cx + cw - 4, ty, cx + cw - 2, ty + thumbH, 0xFF6A6A6A);
	}

	/** Registers a click / hover area; parts outside the scrolling pane are trimmed so they can't be clicked. */
	private void hit(int x, int y, int w, int h, Runnable action, List<Component> tooltip) {
		if (offscreen) return;
		boolean inContent = x >= cx && x < cx + cw && y < cy + ch + 40 && y + h > cy - 40 && !(y >= iy);
		if (inContent) {
			int top = Math.max(y, cy + 1), bottom = Math.min(y + h, cy + ch - 1);
			if (bottom <= top) return;
			hits.add(new Hit(x, top, w, bottom - top, action, tooltip));
		} else {
			hits.add(new Hit(x, y, w, h, action, tooltip));
		}
	}

	// ---------- drawing helpers

	/** Inventory-style raised frame. */
	static void panel(Canvas g, int x, int y, int w, int h) {
		g.fill(x + 1, y, x + w - 1, y + h, BLACK);
		g.fill(x, y + 1, x + w, y + h - 1, BLACK);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
		g.fill(x + 1, y + 1, x + w - 3, y + 3, HI);
		g.fill(x + 1, y + 1, x + 3, y + h - 3, HI);
		g.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, SHADOW);
		g.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, SHADOW);
	}

	static void bevel(Canvas g, int x, int y, int w, int h, int face) {
		g.fill(x, y, x + w, y + h, BLACK);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
		g.fill(x + 1, y + 1, x + w - 1, y + 2, HI);
		g.fill(x + 1, y + 1, x + 2, y + h - 1, HI);
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, SHADOW);
		g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, SHADOW);
	}

	/** Sunken dark pane. */
	static void pane(Canvas g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, 0xFF373737);
		g.fill(x + 1, y + 1, x + w, y + h, HI);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANE);
	}

	static void dot(Canvas g, int x, int y, int color) {
		g.fill(x + 1, y, x + 4, y + 5, color);
		g.fill(x, y + 1, x + 5, y + 4, color);
	}

	/** The Claude mark, drawn as pixel rays. */
	static void starburst(Canvas g, int x, int y, int size, int color) {
		int c = size / 2, t = Math.max(1, size / 8);
		for (int i = 1; i < size - 1; i++) {
			g.fill(x + i, y + c - t / 2, x + i + 1, y + c - t / 2 + t, color);
			g.fill(x + c - t / 2, y + i, x + c - t / 2 + t, y + i + 1, color);
			if (i > 2 && i < size - 3) {
				g.fill(x + i, y + i, x + i + t, y + i + t, color);
				g.fill(x + size - 1 - i, y + i, x + size - 1 - i + t, y + i + t, color);
			}
		}
		g.fill(x + c - t, y + c - t, x + c + t, y + c + t, color);
	}

	private void icon(Canvas g, Section s, int x, int y, int size) {
		if (s == Section.HOME) {
			starburst(g, x, y, size, ORANGE);
			return;
		}
		Item item = switch (s) {
			case PROJECTS -> Items.LODESTONE;
			case SESSIONS -> Items.BOOKSHELF;
			case PRS -> Items.MAP;
			case SETTINGS -> Items.COMPARATOR;
			default -> Items.COMPASS;
		};
		g.item(new ItemStack(item), x, y, size);
	}

	// ---------- text helpers

	private String fit(String s, int width) {
		if (width <= 0) return "";
		if (font.width(s) <= width) return s;
		return font.plainSubstrByWidth(s, Math.max(0, width - font.width("…"))) + "…";
	}

	private String project() {
		if (m.cwd.isEmpty()) return "this project";
		String name = m.cwd.substring(m.cwd.lastIndexOf('/') + 1);
		return m.cwd.contains("/.claude/worktrees/") ? m.cwd.split("/\\.claude/worktrees/")[0].replaceAll(".*/", "") + " (worktree " + name + ")" : name;
	}

	private String tilde(String path) {
		return !m.homeDir.isEmpty() && path.startsWith(m.homeDir) ? "~" + path.substring(m.homeDir.length()) : path;
	}

	private String modelLabel() {
		if (!m.model.equals("default")) {
			Optional<Model.ModelOption> o = m.models.stream().filter(x -> x.value().equals(m.model)).findFirst();
			return o.map(Model.ModelOption::name).orElse(m.model);
		}
		return m.modelName.isEmpty() ? "Default model" : m.modelName.replaceFirst("^claude-", "");
	}

	private static String modeLabel(String mode) {
		for (String[] x : MODES) if (x[0].equals(mode)) return x[1];
		return mode;
	}

	private static String modeHint(String mode) {
		for (String[] x : MODES) if (x[0].equals(mode)) return x[2];
		return mode;
	}

	private static String label(Section s) {
		return switch (s) {
			case HOME -> "Home";
			case SESSION -> "Session";
			case PROJECTS -> "Projects";
			case PRS -> "Pull Requests";
			case SESSIONS -> "Sessions";
			case SETTINGS -> "Settings";
		};
	}

	private static Item blockFor(String name) {
		return BLOCKS[Math.floorMod(name.hashCode(), BLOCKS.length)];
	}

	static String ago(long ms) {
		long s = (System.currentTimeMillis() - ms) / 1000;
		if (s < 60) return "now";
		if (s < 3600) return s / 60 + "m ago";
		if (s < 86400) return s / 3600 + "h ago";
		if (s < 86400L * 30) return s / 86400 + "d ago";
		if (s < 86400L * 365) return s / (86400L * 30) + "mo ago";
		return s / (86400L * 365) + "y ago";
	}

	private static void openApp() {
		try {
			new ProcessBuilder("open", "-a", "Claude UI").start();
		} catch (Exception ignored) {
			// not on macOS or the app isn't installed
		}
	}
}
