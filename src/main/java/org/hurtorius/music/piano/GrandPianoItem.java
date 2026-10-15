package org.hurtorius.music.piano;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.hurtorius.music.registry.MusicContent;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * The piano's item. It puts the piano centred on the block aimed at, or beside it where only that
 * fits (see {@link PianoPlacement}), and places the controller without telling the neighbours yet: a
 * neighbour that changed while the piano is half built could take the parts already there for a
 * broken piano (a tall plant whose lower half the controller replaces drops its upper half, a piano
 * cell). The block tells every neighbour once all its parts stand (see {@link GrandPianoBlock}). Its
 * tooltip says how much room the piano takes and how it is used, and what a piano broken to be moved
 * keeps.
 */
public class GrandPianoItem extends BlockItem {
    public GrandPianoItem(GrandPianoBlock block, Properties properties) {
        super(block, properties);
    }

    @Override
    public @Nullable BlockPlaceContext updatePlacementContext(BlockPlaceContext context) {
        PianoPlacement.Spot spot = PianoPlacement.find(context);
        return spot.controller().equals(context.getClickedPos()) ? context : BlockPlaceContext.at(context, spot.controller(), Direction.UP);
    }

    @Override
    protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        return context.getLevel().setBlock(context.getClickedPos(), state, GrandPianoBlock.ASSEMBLE);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        // Short lines: a tooltip does not wrap, and a long line runs off a small window.
        tooltip.accept(Component.translatable("item.music.grand_piano.size", PianoLayout.COLUMNS, PianoLayout.ROWS, PianoLayout.LAYERS)
            .withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("item.music.grand_piano.aim").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("item.music.grand_piano.use").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("item.music.grand_piano.open").withStyle(ChatFormatting.GRAY));
        PianoItemData kept = stack.get(MusicContent.PIANO_DATA);
        if (kept != null) tooltip.accept(kept.summary().withStyle(ChatFormatting.GRAY));
    }
}
