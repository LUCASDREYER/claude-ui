package claudeui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Using a Claude Screen wall directly: aim at its front (up to {@link #REACH} blocks away) and a pointer follows your
 * crosshair. Left-click clicks the UI, the mouse wheel scrolls it, right-click opens the full UI. Sneaking leaves
 * the mouse alone, so you can still break and place blocks.
 */
public final class TvInput {
	static final double REACH = 64;

	/** The wall being aimed at, with the aimed point in canvas pixels. */
	record Target(BlockPos origin, Direction facing, int w, int h, double x, double y) {
		int canvasW() {
			return ScreenRenderer.canvasWidth(w, h);
		}
	}

	private static Target framePointer;
	private static long frameStamp = -1;

	/** Called at the start of each client tick, before the game turns clicks into breaking or placing. */
	static void tick(Minecraft mc) {
		if (mc.screen != null || mc.player == null || mc.level == null || mc.player.isShiftKeyDown()) return;
		Target t = find(mc, 1.0f);
		if (t == null) return;
		while (mc.options.keyAttack.consumeClick()) ScreenRenderer.view(t.canvasW()).clickAt(t.x(), t.y());
		mc.options.keyAttack.setDown(false); // holding the button mustn't start breaking the wall
		while (mc.options.keyUse.consumeClick()) ClaudeUIClient.open(mc);
		mc.options.keyUse.setDown(false);
	}

	/** Mouse wheel while aiming at a wall; returns true when it was used, so the hotbar doesn't change. */
	public static boolean scroll(double amount) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen != null || mc.player == null || mc.level == null || mc.player.isShiftKeyDown()) return false;
		Target t = find(mc, 1.0f);
		if (t == null) return false;
		ScreenRenderer.view(t.canvasW()).mouseScrolled(t.x(), t.y(), 0, amount);
		return true;
	}

	/** The aimed wall for this frame, computed once and shared by every wall's renderer. */
	static Target pointer(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		long stamp = System.nanoTime() / 4_000_000; // about once per frame
		if (stamp != frameStamp) {
			frameStamp = stamp;
			framePointer = mc.screen == null && mc.player != null && mc.level != null ? find(mc, partialTick) : null;
		}
		return framePointer;
	}

	static Target find(Minecraft mc, float partialTick) {
		HitResult hit = mc.player.pick(REACH, partialTick, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return null;
		Level level = mc.level;
		BlockState state = level.getBlockState(blockHit.getBlockPos());
		if (!(state.getBlock() instanceof ScreenBlock)) return null;
		Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
		if (blockHit.getDirection() != facing) return null; // only the front is a screen

		// Walk to the wall's bottom-left block, which owns the measurement.
		Direction right = facing.getCounterClockWise();
		BlockPos origin = blockHit.getBlockPos();
		for (int i = 0; i < ScreenRenderer.MAX_W && same(level, origin.relative(right.getOpposite()), facing); i++) origin = origin.relative(right.getOpposite());
		for (int i = 0; i < ScreenRenderer.MAX_H && same(level, origin.below(), facing); i++) origin = origin.below();
		int[] size = ScreenRenderer.wall(level, origin, facing);
		if (size == null || !ScreenRenderer.valid(size[0], size[1])) return null;
		int w = size[0], h = size[1];

		Vec3 r = Vec3.atLowerCornerOf(right.getUnitVec3i());
		Vec3 corner = Vec3.atCenterOf(origin).add(facing.getUnitVec3().scale(0.5)).subtract(r.scale(0.5)).subtract(0, 0.5, 0);
		Vec3 d = blockHit.getLocation().subtract(corner);
		double u = d.dot(r), v = d.y;
		if (u < 0 || u > w || v < 0 || v > h) return null;
		int canvasW = ScreenRenderer.canvasWidth(w, h);
		return new Target(origin, facing, w, h, u / w * canvasW, (h - v) / h * ScreenRenderer.CANVAS_H);
	}

	/** World position of a canvas point on a wall (used by the self-test to aim). */
	static Vec3 worldPoint(BlockPos origin, Direction facing, int w, int h, double x, double y) {
		Vec3 r = Vec3.atLowerCornerOf(facing.getCounterClockWise().getUnitVec3i());
		Vec3 corner = Vec3.atCenterOf(origin).add(facing.getUnitVec3().scale(0.5)).subtract(r.scale(0.5)).subtract(0, 0.5, 0);
		double u = x / ScreenRenderer.canvasWidth(w, h) * w, v = h - y / ScreenRenderer.CANVAS_H * h;
		return corner.add(r.scale(u)).add(0, v, 0);
	}

	private static boolean same(Level level, BlockPos pos, Direction facing) {
		BlockState state = level.getBlockState(pos);
		return state.getBlock() instanceof ScreenBlock && state.getValue(HorizontalDirectionalBlock.FACING) == facing;
	}
}
