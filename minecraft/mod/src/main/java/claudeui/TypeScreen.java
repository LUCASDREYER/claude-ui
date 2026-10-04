package claudeui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** A chat-style box for typing to a Claude Screen wall; what you type shows on the wall as you go. */
final class TypeScreen extends Screen {
	private EditBox box;

	private TypeScreen() {
		super(Component.literal("Message Claude"));
	}

	static void open() {
		Minecraft.getInstance().setScreen(new TypeScreen());
	}

	@Override
	protected void init() {
		box = new EditBox(font, 6, height - 14, width - 12, 12, Component.literal("Message"));
		box.setBordered(false);
		box.setMaxLength(4000);
		box.setValue(ClaudeScreen.draft());
		box.setResponder(ClaudeScreen::setDraft);
		addRenderableWidget(box);
		setInitialFocus(box);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			ClaudeScreen.send(box.getValue());
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	/** Just a strip at the bottom, so the wall stays in view. */
	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.fill(2, height - 16, width - 2, height - 2, 0xA0000000);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		String hint = "Message Claude · Enter to send · Esc to cancel (keeps your text)";
		g.fill(2, height - 29, 10 + font.width(hint), height - 17, 0xA0000000);
		g.drawString(font, hint, 6, height - 27, 0xFFB4B4B4, false);
		super.render(g, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
