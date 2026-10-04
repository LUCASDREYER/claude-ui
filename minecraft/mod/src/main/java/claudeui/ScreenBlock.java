package claudeui;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Claude Screen: build a flat wall of these in a TV-shaped rectangle and it shows the Claude Code UI.
 * Right-click opens the UI to type.
 */
public final class ScreenBlock extends HorizontalDirectionalBlock implements EntityBlock {
	public static final MapCodec<ScreenBlock> CODEC = simpleCodec(ScreenBlock::new);
	/** Set by the client entry point; the common code can't reference client classes. */
	public static Runnable openUi = () -> {};

	public ScreenBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/** Faces the player; placed against another screen, it copies that screen's facing so walls line up. */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockState against = context.getLevel().getBlockState(context.getClickedPos().relative(context.getClickedFace().getOpposite()));
		Direction facing = against.getBlock() instanceof ScreenBlock ? against.getValue(FACING) : context.getHorizontalDirection().getOpposite();
		return defaultBlockState().setValue(FACING, facing);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new ScreenBlockEntity(pos, state);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide()) openUi.run();
		return InteractionResult.SUCCESS;
	}
}
