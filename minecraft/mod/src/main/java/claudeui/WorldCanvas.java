package claudeui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws Canvas calls onto a Claude Screen wall. The pose maps canvas pixels (y down) onto the wall's front face;
 * everything is full-bright like a lit display. The clip rectangle is applied by trimming fills and dropping text
 * lines outside it (there is no scissor in the world).
 *
 * Depth: a shape sits one layer in front of the highest shape it overlaps. Layers then follow the UI's nesting
 * (about ten deep) rather than the draw count, so each step can be big enough for the depth buffer to tell apart
 * from across a room while the whole picture stays thin.
 */
final class WorldCanvas implements Canvas {
	/** One layer, in blocks. */
	private static final float LAYER_BLOCKS = 0.003f;

	private final PoseStack pose;
	private final SubmitNodeCollector out;
	private final Font font;
	private final int light;
	private final float layer;
	private final List<int[]> placed = new ArrayList<>(); // x1, y1, x2, y2, layer
	// "No clip" is a large finite box, so edge arithmetic like clipY1 - 1 can't overflow.
	private static final int OPEN = 1_000_000;
	private int clipX1 = -OPEN, clipY1 = -OPEN, clipX2 = OPEN, clipY2 = OPEN;

	/** @param unitsPerBlock canvas z units per block, from the pose's z scale */
	WorldCanvas(PoseStack pose, SubmitNodeCollector out, Font font, int light, float unitsPerBlock) {
		this.pose = pose;
		this.out = out;
		this.font = font;
		this.light = light;
		this.layer = LAYER_BLOCKS * unitsPerBlock;
	}

	@Override
	public void fill(int x1, int y1, int x2, int y2, int color) {
		float ax = Math.max(x1, clipX1), ay = Math.max(y1, clipY1), bx = Math.min(x2, clipX2), by = Math.min(y2, clipY2);
		if (bx <= ax || by <= ay || (color >>> 24) == 0) return;
		float depth = depth((int) ax, (int) ay, (int) bx, (int) by);
		out.submitCustomGeometry(pose, RenderTypes.textBackground(), (p, vc) -> {
			quad(vc, p, ax, ay, bx, by, depth, color);
		});
	}

	/** Both windings, so the quad shows whichever way the mirrored pose leaves it facing. */
	private void quad(VertexConsumer vc, PoseStack.Pose p, float ax, float ay, float bx, float by, float depth, int color) {
		vc.addVertex(p, ax, ay, depth).setColor(color).setLight(light);
		vc.addVertex(p, ax, by, depth).setColor(color).setLight(light);
		vc.addVertex(p, bx, by, depth).setColor(color).setLight(light);
		vc.addVertex(p, bx, ay, depth).setColor(color).setLight(light);
		vc.addVertex(p, ax, ay, depth).setColor(color).setLight(light);
		vc.addVertex(p, bx, ay, depth).setColor(color).setLight(light);
		vc.addVertex(p, bx, by, depth).setColor(color).setLight(light);
		vc.addVertex(p, ax, by, depth).setColor(color).setLight(light);
	}

	@Override
	public void text(String text, int x, int y, int color) {
		text(Component.literal(text).getVisualOrderText(), x, y, color);
	}

	@Override
	public void text(FormattedCharSequence text, int x, int y, int color) {
		if (y < clipY1 - 1 || y + 8 > clipY2 + 1) return;
		pose.pushPose();
		pose.translate(0, 0, depth(x, y, x + font.width(text), y + 9));
		out.submitText(pose, x, y, text, false, Font.DisplayMode.POLYGON_OFFSET, light, color, 0, 0);
		pose.popPose();
	}

	@Override
	public void scaledText(String text, int x, int y, float scale, int color) {
		pose.pushPose();
		pose.translate(x, y, depth(x, y, x + (int) (font.width(text) * scale), y + (int) (9 * scale)));
		pose.scale(scale, scale, 1);
		out.submitText(pose, 0, 0, Component.literal(text).getVisualOrderText(), false, Font.DisplayMode.POLYGON_OFFSET, light, color, 0, 0);
		pose.popPose();
	}

	@Override
	public void item(ItemStack stack, int x, int y, int size) {
		if (y < clipY1 || y + size > clipY2) return;
		Minecraft mc = Minecraft.getInstance();
		// A fresh state per icon: submissions are drawn later in the frame, so a shared one would end up as the last item.
		ItemStackRenderState itemState = new ItemStackRenderState();
		mc.getItemModelResolver().updateForTopItem(itemState, stack, ItemDisplayContext.GUI, mc.level, null, 0);
		if (itemState.isEmpty()) return;
		pose.pushPose();
		pose.translate(x + size / 2f, y + size / 2f, depth(x, y, x + size, y + size) + layer / 2);
		// Same transform the GUI uses for item icons, flattened so blocks don't poke out of the screen.
		pose.scale(size, -size, size * 0.05f);
		itemState.submit(pose, out, light, OverlayTexture.NO_OVERLAY, 0);
		pose.popPose();
	}

	@Override
	public void face(PlayerSkin skin, int x, int y, int size) {
		Identifier texture = skin.body().texturePath();
		float depth = depth(x, y, x + size, y + size);
		int x2 = x + size, y2 = y + size;
		out.submitCustomGeometry(pose, RenderTypes.text(texture), (p, vc) -> {
			// face (8..16, 8..16) then the hat layer (40..48, 8..16) of a 64x64 skin
			for (float u : new float[] {8f / 64, 40f / 64}) {
				float u2 = u + 8f / 64, v = 8f / 64, v2 = 16f / 64, d = depth + (u > 0.5f ? layer / 2 : 0);
				vc.addVertex(p, x, y, d).setColor(-1).setUv(u, v).setLight(light);
				vc.addVertex(p, x, y2, d).setColor(-1).setUv(u, v2).setLight(light);
				vc.addVertex(p, x2, y2, d).setColor(-1).setUv(u2, v2).setLight(light);
				vc.addVertex(p, x2, y, d).setColor(-1).setUv(u2, v).setLight(light);
				vc.addVertex(p, x, y, d).setColor(-1).setUv(u, v).setLight(light);
				vc.addVertex(p, x2, y, d).setColor(-1).setUv(u2, v).setLight(light);
				vc.addVertex(p, x2, y2, d).setColor(-1).setUv(u2, v2).setLight(light);
				vc.addVertex(p, x, y2, d).setColor(-1).setUv(u, v2).setLight(light);
			}
		});
	}

	@Override
	public void clip(int x1, int y1, int x2, int y2) {
		clipX1 = x1;
		clipY1 = y1;
		clipX2 = x2;
		clipY2 = y2;
	}

	@Override
	public void unclip() {
		clipX1 = clipY1 = -OPEN;
		clipX2 = clipY2 = OPEN;
	}

	/** One layer above the highest shape already under this rectangle. */
	private float depth(int x1, int y1, int x2, int y2) {
		int level = 0;
		for (int[] r : placed) {
			if (r[4] >= level && r[0] < x2 && r[2] > x1 && r[1] < y2 && r[3] > y1) level = r[4] + 1;
		}
		placed.add(new int[] {x1, y1, x2, y2, level});
		return level * layer;
	}
}
