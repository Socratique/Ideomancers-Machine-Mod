package com.ideomancer.machinemod.block;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * Hollowstone item. A filled Hollowstone mined with Silk Touch keeps its inner block in the item
 * (BlockEntityTag.InnerBlock), as framed block items do, and is named after it:
 * "Hollowstone with Glass". Placing it restores the filled block.
 *
 * Written by Claude, directed and guided by The Ideomancer.
 */
public class HollowstoneBlockItem extends BlockItem {

    public HollowstoneBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        Block inner = HollowstoneBlock.innerFromStack(stack);
        if (inner != null) {
            return Component.translatable("item.machinemod.hollow_stone.filled", inner.getName());
        }
        return super.getName(stack);
    }
}
