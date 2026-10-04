package claudeui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Client entry point: K (rebindable under Controls) opens the Claude Code screen; Claude Screen walls draw it in the world. */
public final class ClaudeUIClient implements ClientModInitializer {
	public static final Connection CONNECTION = new Connection();
	private static KeyMapping openKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("claudeui", "main"));
		BlockEntityRenderers.register(ClaudeUIMod.SCREEN_ENTITY, ScreenRenderer::new);
		ScreenBlock.openUi = () -> open(Minecraft.getInstance());
		ClaudeUIMod.screenAvailable = () -> {
			Minecraft mc = Minecraft.getInstance();
			return mc.getConnection() == null || mc.hasSingleplayerServer() || ClientPlayNetworking.canSend(HelloPayload.TYPE);
		};
		openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.claudeui.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) open(client);
			AutoTest.tick(client);
		});
	}

	public static void open(Minecraft client) {
		CONNECTION.connectIfNeeded();
		client.setScreen(new ClaudeScreen());
	}
}
