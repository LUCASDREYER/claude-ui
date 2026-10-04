package claudeui;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/** Common entry point: registers the Claude Screen block (runs on both client and the built-in server). */
public final class ClaudeUIMod implements ModInitializer {
	public static final Identifier SCREEN_ID = Identifier.fromNamespaceAndPath("claudeui", "screen");
	private static final ResourceKey<Block> SCREEN_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, SCREEN_ID);
	private static final ResourceKey<Item> SCREEN_ITEM_KEY = ResourceKey.create(Registries.ITEM, SCREEN_ID);

	public static final Block SCREEN = Registry.register(BuiltInRegistries.BLOCK, SCREEN_BLOCK_KEY, new ScreenBlock(
		BlockBehaviour.Properties.of().setId(SCREEN_BLOCK_KEY).mapColor(MapColor.COLOR_BLACK).strength(0.8f).sound(SoundType.GLASS).lightLevel(state -> 3)));
	public static final Item SCREEN_ITEM = Registry.register(BuiltInRegistries.ITEM, SCREEN_ITEM_KEY,
		new BlockItem(SCREEN, new Item.Properties().setId(SCREEN_ITEM_KEY).useBlockDescriptionPrefix()));
	public static final BlockEntityType<ScreenBlockEntity> SCREEN_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, SCREEN_ID,
		FabricBlockEntityTypeBuilder.create(ScreenBlockEntity::new, SCREEN).build());

	@Override
	public void onInitialize() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> entries.accept(SCREEN_ITEM));
	}
}
