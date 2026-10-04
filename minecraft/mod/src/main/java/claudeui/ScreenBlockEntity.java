package claudeui;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Holds no data; it exists so the client can draw the screen with a block entity renderer. */
public final class ScreenBlockEntity extends BlockEntity {
	public ScreenBlockEntity(BlockPos pos, BlockState state) {
		super(ClaudeUIMod.SCREEN_ENTITY, pos, state);
	}
}
