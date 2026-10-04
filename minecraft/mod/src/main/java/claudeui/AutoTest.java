package claudeui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Development aid, off unless -Dclaudeui.autotest=true: opens the screen, steps through the sections,
 * saves a screenshot of each to run/screenshots, then quits. Only reads data; it never sends a prompt.
 */
final class AutoTest {
	private static final boolean ON = Boolean.getBoolean("claudeui.autotest");
	/** -Dclaudeui.live=<folder>: one real prompt in that folder, approving its permission request. */
	private static final String LIVE = System.getProperty("claudeui.live");
	/** -Dclaudeui.tvtest=true: builds a Claude Screen wall in a fresh flat world and screenshots it. */
	private static final boolean TV = Boolean.getBoolean("claudeui.tvtest");
	/** -Dclaudeui.servertest=true with --quickPlayMultiplayer: logs whether the block is offered there, then quits. */
	private static final boolean SERVER = Boolean.getBoolean("claudeui.servertest");
	private static int tick, doneAt = -1, inWorld;
	private static boolean sawRunning, approved;

	static void tick(Minecraft mc) {
		if ((!ON && LIVE == null && !TV && !SERVER) || mc.getOverlay() != null) return;
		tick++;
		Model m = ClaudeUIClient.CONNECTION.model;
		if (SERVER) {
			if (mc.player != null && ++inWorld == 60) {
				net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), true, mc.level.registryAccess());
				boolean listed = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB
					.getValueOrThrow(net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS).getDisplayItems().stream()
					.anyMatch(stack -> stack.is(ClaudeUIMod.SCREEN_ITEM));
				System.out.println("[claudeui servertest] singleplayer=" + mc.hasSingleplayerServer() + " available=" + ClaudeUIMod.screenAvailable.getAsBoolean() + " inCreativeTab=" + listed);
			}
			if (inWorld == 80) mc.stop();
			return;
		}
		if (TV) {
			tv(mc, m);
			return;
		}
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

	private static void tv(Minecraft mc, Model m) {
		if (tick == 30) {
			String name = "claudeui-tv-" + System.currentTimeMillis();
			LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
				new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
			mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(42L, false, false), WorldPresets::createFlatWorldDimensions, mc.screen);
			return;
		}
		if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
		inWorld++;
		if (inWorld == 40) {
			BlockPos p = mc.player.blockPosition();
			int x = p.getX(), y = p.getY(), z = p.getZ();
			MinecraftServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				for (String cmd : new String[] {
					"time set noon",
					"weather clear",
					// an 8x4 wall (ratio 2) facing north, 7 blocks south of the player
					"fill " + (x - 4) + " " + y + " " + (z + 7) + " " + (x + 3) + " " + (y + 3) + " " + (z + 7) + " claudeui:screen[facing=north]",
					// a 3x3 wall to the side: wrong ratio, so it stays dark
					"fill " + (x + 6) + " " + y + " " + (z + 7) + " " + (x + 8) + " " + (y + 2) + " " + (z + 7) + " claudeui:screen[facing=north]",
					"tp @p " + x + " " + y + " " + (z + 0.5) + " 0 -4",
				}) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
			});
			mc.options.hideGui = true;
		}
		if (inWorld == 100) {
			net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), true, mc.level.registryAccess());
			boolean listed = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB
				.getValueOrThrow(net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS).getDisplayItems().stream()
				.anyMatch(stack -> stack.is(ClaudeUIMod.SCREEN_ITEM));
			System.out.println("[claudeui servertest] singleplayer=" + mc.hasSingleplayerServer() + " available=" + ClaudeUIMod.screenAvailable.getAsBoolean() + " inCreativeTab=" + listed);
		}
		if (inWorld == 140) shot(mc, "tv-1-home");
		if (inWorld == 145 && !m.recent.isEmpty()) {
			m.resume(m.recent.get(0).id(), m.recent.get(0).cwd());
			ClaudeScreen.select(ClaudeScreen.Section.SESSION);
		}
		if (inWorld == 220) shot(mc, "tv-2-session");
		if (inWorld == 240) mc.stop();
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "claudeui-" + name + ".png", mc.getMainRenderTarget(), 1, msg -> {});
	}
}
