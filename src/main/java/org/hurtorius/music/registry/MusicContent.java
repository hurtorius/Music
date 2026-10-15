package org.hurtorius.music.registry;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import org.hurtorius.music.piano.GrandPianoBlock;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.GrandPianoItem;
import org.hurtorius.music.piano.PianoItemData;
import org.hurtorius.music.seat.PianoSeatEntity;

/**
 * The one block, its item, its block entity, the bench seat, the item component that keeps a moved
 * piano's setup, and the creative tab (the piano is also in Functional Blocks, after the jukebox).
 */
public final class MusicContent {
    public static final String MOD_ID = "music";
    public static final Identifier GRAND_PIANO_ID = id("grand_piano");

    public static final GrandPianoBlock GRAND_PIANO = new GrandPianoBlock(BlockBehaviour.Properties.of()
        .setId(ResourceKey.create(Registries.BLOCK, GRAND_PIANO_ID))
        .mapColor(MapColor.COLOR_BLACK).strength(2.5f, 6f).sound(SoundType.WOOD).noOcclusion().forceSolidOn()
        .pushReaction(PushReaction.BLOCK).ignitedByLava());
    public static final BlockItem GRAND_PIANO_ITEM = new GrandPianoItem(GRAND_PIANO, new Item.Properties()
        .setId(ResourceKey.create(Registries.ITEM, GRAND_PIANO_ID)).useBlockDescriptionPrefix().stacksTo(1));
    public static final BlockEntityType<GrandPianoBlockEntity> GRAND_PIANO_ENTITY =
        FabricBlockEntityTypeBuilder.create(GrandPianoBlockEntity::new, GRAND_PIANO).build();
    private static final ResourceKey<EntityType<?>> SEAT_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("piano_seat"));
    public static final EntityType<PianoSeatEntity> PIANO_SEAT = EntityType.Builder.<PianoSeatEntity>of(PianoSeatEntity::new, MobCategory.MISC)
        .noLootTable().sized(0f, 0f).noSummon().clientTrackingRange(10).updateInterval(20).build(SEAT_KEY);
    /** {@code music:piano}: the settings and playlist a piano broken to be moved keeps (see {@link PianoItemData}). */
    public static final DataComponentType<PianoItemData> PIANO_DATA = DataComponentType.<PianoItemData>builder()
        .persistent(PianoItemData.CODEC).networkSynchronized(PianoItemData.STREAM_CODEC).build();

    private MusicContent() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static void register() {
        Registry.register(BuiltInRegistries.BLOCK, GRAND_PIANO_ID, GRAND_PIANO);
        Registry.register(BuiltInRegistries.ITEM, GRAND_PIANO_ID, GRAND_PIANO_ITEM);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, GRAND_PIANO_ID, GRAND_PIANO_ENTITY);
        Registry.register(BuiltInRegistries.ENTITY_TYPE, SEAT_KEY, PIANO_SEAT);
        Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id("piano"), PIANO_DATA);
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("music"), FabricItemGroup.builder()
            .title(Component.translatable("itemGroup.music"))
            .icon(() -> new ItemStack(GRAND_PIANO_ITEM))
            .displayItems((parameters, output) -> output.accept(GRAND_PIANO_ITEM))
            .build());
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> entries.addAfter(Items.JUKEBOX, GRAND_PIANO_ITEM));
    }
}
