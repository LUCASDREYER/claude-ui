package claudeui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;

/**
 * The few drawing calls the Claude UI uses. {@link Gui} draws on the normal screen; {@link WorldCanvas} draws the
 * same calls on a Claude Screen wall in the world, so both show the identical layout.
 */
public interface Canvas {
	void fill(int x1, int y1, int x2, int y2, int color);

	void text(String text, int x, int y, int color);

	void text(FormattedCharSequence text, int x, int y, int color);

	/** Text scaled around its top-left corner (the big "Claude Code" title). */
	void scaledText(String text, int x, int y, float scale, int color);

	void item(ItemStack stack, int x, int y, int size);

	void face(PlayerSkin skin, int x, int y, int size);

	void clip(int x1, int y1, int x2, int y2);

	void unclip();

	default void outline(int x, int y, int w, int h, int color) {
		fill(x, y, x + w, y + 1, color);
		fill(x, y + h - 1, x + w, y + h, color);
		fill(x, y + 1, x + 1, y + h - 1, color);
		fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	/** On-screen drawing through the game's GUI renderer. */
	record Gui(GuiGraphics g, Font font) implements Canvas {
		@Override
		public void fill(int x1, int y1, int x2, int y2, int color) {
			g.fill(x1, y1, x2, y2, color);
		}

		@Override
		public void text(String text, int x, int y, int color) {
			g.drawString(font, text, x, y, color, false);
		}

		@Override
		public void text(FormattedCharSequence text, int x, int y, int color) {
			g.drawString(font, text, x, y, color, false);
		}

		@Override
		public void scaledText(String text, int x, int y, float scale, int color) {
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(scale, scale);
			g.drawString(font, text, 0, 0, color, false);
			g.pose().popMatrix();
		}

		@Override
		public void item(ItemStack stack, int x, int y, int size) {
			if (size == 16) {
				g.renderItem(stack, x, y);
				return;
			}
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(size / 16f, size / 16f);
			g.renderItem(stack, 0, 0);
			g.pose().popMatrix();
		}

		@Override
		public void face(PlayerSkin skin, int x, int y, int size) {
			PlayerFaceRenderer.draw(g, skin, x, y, size);
		}

		@Override
		public void clip(int x1, int y1, int x2, int y2) {
			g.enableScissor(x1, y1, x2, y2);
		}

		@Override
		public void unclip() {
			g.disableScissor();
		}
	}
}
