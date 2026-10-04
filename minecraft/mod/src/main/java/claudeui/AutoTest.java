package claudeui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Development aid, off unless -Dclaudeui.autotest=true: opens the screen, steps through the sections,
 * saves a screenshot of each to run/screenshots, then quits. Only reads data; it never sends a prompt.
 */
final class AutoTest {
	private static final boolean ON = Boolean.getBoolean("claudeui.autotest");
	/** -Dclaudeui.live=<folder>: one real prompt in that folder, approving its permission request. */
	private static final String LIVE = System.getProperty("claudeui.live");
	private static int tick, doneAt = -1;
	private static boolean sawRunning, approved;

	static void tick(Minecraft mc) {
		if ((!ON && LIVE == null) || mc.getOverlay() != null) return;
		tick++;
		Model m = ClaudeUIClient.CONNECTION.model;
		if (LIVE != null) {
			live(mc, m);
			return;
		}
		switch (tick) {
			case 40 -> ClaudeUIClient.open(mc);
			case 120 -> shot(mc, "1-home");
			case 125 -> ClaudeScreen.select(ClaudeScreen.Section.PRS);
			case 150 -> shot(mc, "2-prs");
			case 155 -> ClaudeScreen.select(ClaudeScreen.Section.SETTINGS);
			case 180 -> shot(mc, "3-settings");
			case 185 -> {
				if (!m.recent.isEmpty()) m.resume(m.recent.get(0).id(), m.recent.get(0).cwd());
				ClaudeScreen.select(ClaudeScreen.Section.SESSION);
			}
			case 240 -> shot(mc, "4-session");
			case 245 -> {
				// a fake approval request, drawn locally and never sent anywhere
				Model.Pending p = new Model.Pending();
				p.id = "autotest";
				p.toolName = "Bash";
				p.what = "Bash npm test";
				p.detail = "$ npm test";
				m.pending.add(p);
			}
			case 270 -> shot(mc, "5-approval");
			case 275 -> {
				m.pending.removeIf(p -> p.id.equals("autotest"));
				ClaudeScreen.openAttach();
			}
			case 300 -> shot(mc, "6-attach");
			case 310 -> mc.stop();
			default -> {}
		}
	}

	private static void live(Minecraft mc, Model m) {
		if (tick == 40) ClaudeUIClient.open(mc);
		if (tick == 80) {
			m.newSession(LIVE, false, "Create a file named hello.txt containing the word hi, then reply with one short sentence.");
			ClaudeScreen.select(ClaudeScreen.Section.SESSION);
		}
		if (m.running) sawRunning = true;
		if (!m.pending.isEmpty() && !approved) {
			shot(mc, "live-1-approval");
			m.answer(m.pending.get(0), true, null, null);
			approved = true;
		}
		if (sawRunning && !m.running && doneAt < 0) doneAt = tick;
		if (doneAt > 0 && tick == doneAt + 30 || tick == 2400) {
			shot(mc, "live-2-done");
			doneAt = Integer.MAX_VALUE - 100;
		}
		if (tick == 2440 || doneAt == Integer.MAX_VALUE - 100 && tick % 40 == 0) mc.stop();
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "claudeui-" + name + ".png", mc.getMainRenderTarget(), 1, msg -> {});
	}
}
