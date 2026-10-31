package com.kalloer.compressedagriculture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StemBlock;

/**
 * 读 {@code StemBlock} 的 {@code private final ResourceKey<Block> fruit} 字段。
 *
 * <p>这是「果实由茎自己给出、代码里不出现果实方块 ID」的唯一读出口（ADR-0009 / GLOSSARY「瓜类茎」）。
 */
@Mixin(StemBlock.class)
public interface StemBlockAccessor {
    @Accessor("fruit")
    ResourceKey<Block> compressedagriculture$fruit();
}
