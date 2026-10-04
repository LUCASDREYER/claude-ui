package claudeui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the Claude UI across a wall of Claude Screens. Only the bottom-left block of a valid rectangle draws; the
 * rest are just the dark glass of their model. Valid means: a full, flat rectangle of screens facing the same way,
 * at least 2 wide, with a TV-like width:height between 1.5 and 2 (2x1, 3x2, 4x2, 5x3, 7x4, 16x9, ...).
 */
public final class ScreenRenderer implements BlockEntityRenderer<ScreenBlockEntity, ScreenRenderer.State> {
	static final int MAX_W = 32, MAX_H = 18;
	/** Canvas height in UI pixels; the width follows the wall's ratio. */
	private static final int CANVAS_H = 270;

	public static final class State extends BlockEntityRenderState {
		int w, h;
		Direction facing = Direction.NORTH;
	}

	private final Font font;
	private ClaudeScreen view;
	private int viewW, viewH;

	public ScreenRenderer(BlockEntityRendererProvider.Context context) {
		this.font = context.font();
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(ScreenBlockEntity entity, State state, float partialTick, Vec3 camera, ModelFeatureRenderer.CrumblingOverlay crumbling) {
		BlockEntityRenderer.super.extractRenderState(entity, state, partialTick, camera, crumbling);
		state.w = state.h = 0;
		Level level = entity.getLevel();
		if (level == null) return;
		state.facing = entity.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
		int[] size = measure(level, entity.getBlockPos(), state.facing);
		if (size != null) {
			state.w = size[0];
			state.h = size[1];
		}
	}

	/** Width and height when `pos` is the bottom-left (as seen from the front) of a valid screen wall, else null. */
	static int[] measure(Level level, BlockPos pos, Direction facing) {
		Direction right = facing.getCounterClockWise(); // the viewer's right
		if (is(level, pos.relative(right.getOpposite()), facing) || is(level, pos.below(), facing)) return null;
		int w = 1, h = 1;
		while (w < MAX_W && is(level, pos.relative(right, w), facing)) w++;
		while (h < MAX_H && is(level, pos.above(h), facing)) h++;
		for (int dx = 0; dx < w; dx++) {
			for (int dy = 0; dy < h; dy++) if (!is(level, pos.relative(right, dx).above(dy), facing)) return null;
			if (is(level, pos.relative(right, dx).below(), facing) || is(level, pos.relative(right, dx).above(h), facing)) return null;
		}
		for (int dy = 0; dy < h; dy++) {
			if (is(level, pos.relative(right.getOpposite()).above(dy), facing) || is(level, pos.relative(right, w).above(dy), facing)) return null;
		}
		double ratio = w / (double) h;
		return w >= 2 && ratio >= 1.5 && ratio <= 2.0 ? new int[] {w, h} : null;
	}

	private static boolean is(Level level, BlockPos pos, Direction facing) {
		BlockState state = level.getBlockState(pos);
		return state.getBlock() instanceof ScreenBlock && state.getValue(HorizontalDirectionalBlock.FACING) == facing;
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
		if (state.w == 0) return;
		int canvasW = Math.round(CANVAS_H * state.w / (float) state.h);
		if (view == null || viewW != canvasW || viewH != CANVAS_H) {
			view = new ClaudeScreen(true);
			view.init(canvasW, CANVAS_H);
			viewW = canvasW;
			viewH = CANVAS_H;
		}
		pose.pushPose();
		// Block center, turned so +X is the viewer's right and +Z points out of the screen.
		pose.translate(0.5, 0.5, 0.5);
		pose.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
		// Top-left of the wall, just in front of the glass; then canvas pixels with y pointing down.
		pose.translate(-0.5, -0.5 + state.h, 0.5 + 0.003);
		pose.scale(state.w / (float) canvasW, -state.h / (float) CANVAS_H, 1f / canvasW);
		WorldCanvas canvas = new WorldCanvas(pose, out, font, LightTexture.FULL_BRIGHT, canvasW);
		canvas.fill(0, 0, canvasW, CANVAS_H, 0xFF0C0F14);
		view.paint(canvas, -10000, -10000);
		pose.popPose();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true; // the picture extends well past the bottom-left block it belongs to
	}

	@Override
	public int getViewDistance() {
		return 96;
	}
}
