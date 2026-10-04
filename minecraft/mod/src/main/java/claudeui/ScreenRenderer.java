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
 * Draws the Claude UI across a wall of Claude Screens. Only the bottom-left block of a rectangle draws; the rest are
 * just the dark glass of their model. A full, flat rectangle of screens facing the same way shows the UI when it is
 * 2 to 128 wide, up to 72 tall, and 1.3 to 3 times wider than tall (4:3 TV through ultra-wide cinema). A clean
 * rectangle outside those limits shows what's wrong instead of staying dark.
 */
public final class ScreenRenderer implements BlockEntityRenderer<ScreenBlockEntity, ScreenRenderer.State> {
	static final int MAX_W = 128, MAX_H = 72;
	static final double MIN_RATIO = 1.3, MAX_RATIO = 3.0;
	/** How far to look for a wall's edges, so oversized walls can still say they're too big. */
	private static final int SCAN_W = 160, SCAN_H = 96;
	/** Re-measure a wall at most this often (ticks); a 100-block-wide scan every frame would cost frame rate. */
	private static final long REMEASURE_TICKS = 10;
	private static final java.util.Map<Long, long[]> measured = new java.util.HashMap<>(); // pos -> {w, h, gameTime, facing}
	/** Canvas height in UI pixels; the width follows the wall's ratio. */
	static final int CANVAS_H = 270;
	/** One laid-out UI per canvas width; walls of the same shape share it, and clicks use its last layout. */
	private static final java.util.Map<Integer, ClaudeScreen> views = new java.util.HashMap<>();

	public static final class State extends BlockEntityRenderState {
		int w, h;
		boolean valid;
		Direction facing = Direction.NORTH;
		double pointerX = Double.NaN, pointerY = Double.NaN;
	}

	private final Font font;

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
		state.pointerX = state.pointerY = Double.NaN;
		Level level = entity.getLevel();
		if (level == null) return;
		state.facing = entity.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
		BlockPos pos = entity.getBlockPos();
		Direction right = state.facing.getCounterClockWise();
		// Cheap per-frame test: only the bottom-left block of a wall does anything.
		if (is(level, pos.relative(right.getOpposite()), state.facing) || is(level, pos.below(), state.facing)) return;
		int[] size = wall(level, pos, state.facing);
		if (size == null) return;
		state.w = size[0];
		state.h = size[1];
		state.valid = valid(state.w, state.h);
		TvInput.Target aimed = state.valid ? TvInput.pointer(partialTick) : null;
		if (aimed != null && aimed.origin().equals(pos)) {
			state.pointerX = aimed.x();
			state.pointerY = aimed.y();
		}
	}

	/** Cached size of the wall whose bottom-left block is `origin`, re-measured twice a second; null if it isn't a clean rectangle. */
	static int[] wall(Level level, BlockPos origin, Direction facing) {
		long now = level.getGameTime();
		long[] cached = measured.get(origin.asLong());
		if (cached == null || now - cached[2] >= REMEASURE_TICKS || now < cached[2] || cached[3] != facing.ordinal()) {
			int[] size = measure(level, origin, facing);
			cached = new long[] {size == null ? 0 : size[0], size == null ? 0 : size[1], now, facing.ordinal()};
			if (measured.size() > 256) measured.clear();
			measured.put(origin.asLong(), cached);
		}
		return cached[0] == 0 ? null : new int[] {(int) cached[0], (int) cached[1]};
	}

	static boolean valid(int w, int h) {
		double ratio = w / (double) h;
		return w >= 2 && w <= MAX_W && h <= MAX_H && ratio >= MIN_RATIO && ratio <= MAX_RATIO;
	}

	static int canvasWidth(int w, int h) {
		return Math.max(1, Math.round(CANVAS_H * w / (float) h));
	}

	/** The UI laid out for a canvas of this width. */
	static ClaudeScreen view(int canvasW) {
		return views.computeIfAbsent(canvasW, width -> {
			ClaudeScreen view = new ClaudeScreen(true);
			view.init(width, CANVAS_H);
			return view;
		});
	}

	/** Width and height when `pos` is the bottom-left (as seen from the front) of a clean rectangle of screens, else null. */
	static int[] measure(Level level, BlockPos pos, Direction facing) {
		Direction right = facing.getCounterClockWise(); // the viewer's right
		if (is(level, pos.relative(right.getOpposite()), facing) || is(level, pos.below(), facing)) return null;
		int w = 1, h = 1;
		while (w < SCAN_W && is(level, pos.relative(right, w), facing)) w++;
		while (h < SCAN_H && is(level, pos.above(h), facing)) h++;
		for (int dx = 0; dx < w; dx++) {
			for (int dy = 0; dy < h; dy++) if (!is(level, pos.relative(right, dx).above(dy), facing)) return null;
			if (is(level, pos.relative(right, dx).below(), facing) || is(level, pos.relative(right, dx).above(h), facing)) return null;
		}
		for (int dy = 0; dy < h; dy++) {
			if (is(level, pos.relative(right.getOpposite()).above(dy), facing) || is(level, pos.relative(right, w).above(dy), facing)) return null;
		}
		return w >= 2 || h >= 2 ? new int[] {w, h} : null;
	}

	private static boolean is(Level level, BlockPos pos, Direction facing) {
		BlockState state = level.getBlockState(pos);
		return state.getBlock() instanceof ScreenBlock && state.getValue(HorizontalDirectionalBlock.FACING) == facing;
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
		if (state.w == 0) return;
		int canvasW = canvasWidth(state.w, state.h);
		if (!state.valid) {
			hint(state, pose, out, canvasW);
			return;
		}
		boolean aimed = !Double.isNaN(state.pointerX);
		pose.pushPose();
		// Block center, turned so +X is the viewer's right and +Z points out of the screen; then the wall's top-left.
		place(state, pose, canvasW);
		WorldCanvas canvas = new WorldCanvas(pose, out, font, LightTexture.FULL_BRIGHT, canvasW);
		canvas.fill(0, 0, canvasW, CANVAS_H, 0xFF0C0F14);
		view(canvasW).paint(canvas, aimed ? (int) state.pointerX : -10000, aimed ? (int) state.pointerY : -10000);
		if (aimed) pointer(canvas, (int) state.pointerX, (int) state.pointerY);
		pose.popPose();
	}

	/** A pixel-art mouse arrow at (x, y): black outline, white fill. */
	private static void pointer(Canvas g, int x, int y) {
		String[] rows = {"X", "XX", "XWX", "XWWX", "XWWWX", "XWWWWX", "XWWWWWX", "XWWWWWWX", "XWWWWXXXX", "XWXWWX", "XX XWWX", "X  XWWX", "    XX"};
		for (int r = 0; r < rows.length; r++) {
			for (int c = 0; c < rows[r].length(); c++) {
				char ch = rows[r].charAt(c);
				if (ch != ' ') g.fill(x + c, y + r, x + c + 1, y + r + 1, ch == 'X' ? 0xFF000000 : 0xFFFFFFFF);
			}
		}
	}

	/** A clean rectangle with the wrong size or shape: say what it is and what would work. */
	private void hint(State state, PoseStack pose, SubmitNodeCollector out, int canvasW) {
		pose.pushPose();
		place(state, pose, canvasW);
		WorldCanvas canvas = new WorldCanvas(pose, out, font, LightTexture.FULL_BRIGHT, canvasW);
		double ratio = state.w / (double) state.h;
		String problem = state.w > MAX_W || state.h > MAX_H ? "too big (max " + MAX_W + " × " + MAX_H + ")"
			: ratio < MIN_RATIO ? "too tall for its width" : ratio > MAX_RATIO ? "too wide for its height" : "too small";
		String[] lines = {"Claude Screen  " + state.w + " × " + state.h + ": " + problem, "Make it 1.3 to 3 times wider than tall, e.g. 8 × 4, 16 × 9 or 48 × 20"};
		float scale = Math.min(1.5f, (canvasW - 20) / (float) Math.max(font.width(lines[0]), font.width(lines[1])));
		int y = (int) (CANVAS_H / 2f - 11 * scale);
		for (String line : lines) {
			canvas.scaledText(line, (int) ((canvasW - font.width(line) * scale) / 2), y, scale, 0xFF9AA3AD);
			y += (int) (12 * scale);
		}
		pose.popPose();
	}

	/** Moves the pose to the wall's top-left corner, in canvas pixels with y pointing down. */
	private static void place(State state, PoseStack pose, int canvasW) {
		pose.translate(0.5, 0.5, 0.5);
		pose.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
		pose.translate(-0.5, -0.5 + state.h, 0.5 + 0.003);
		pose.scale(state.w / (float) canvasW, -state.h / (float) CANVAS_H, 1f / canvasW);
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true; // the picture extends well past the bottom-left block it belongs to
	}

	@Override
	public int getViewDistance() {
		return 160; // cinema walls are watched from far away
	}
}
