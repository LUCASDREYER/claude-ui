package claudeui.mixin;

import claudeui.TvInput;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While you aim at a Claude Screen wall, the mouse wheel scrolls it instead of the hotbar. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void claudeui$scrollWall(long window, double xOffset, double yOffset, CallbackInfo ci) {
		if (TvInput.scroll(yOffset)) ci.cancel();
	}
}
