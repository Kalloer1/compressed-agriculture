package com.kalloer.compressedagriculture.crop;

import java.util.Collections;

import javax.annotation.Nullable;

import com.kalloer.compressedagriculture.registry.ModTags;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 「这一格方块是不是本模组要结算的作物」的全部判断，以及结算成功后的重置幼苗。
 *
 * <p>判断顺序固定为：先排除茎 / 树苗 / 可可豆，再看标签与 {@code CropBlock} 成熟（ADR-0009）。
 * 排除写在标签判定之前，因为 {@code #minecraft:crops} 里本来就有 {@code melon_stem} / {@code pumpkin_stem}，
 * 不先排除的话茎会与瓜类模块抢同一个方块。
 */
public final class CompressibleCrops {
    /** 方块状态里 AGE 属性的名字，非 {@code CropBlock} 的作物靠它找年龄属性。 */
    private static final String AGE_PROPERTY_NAME = "age";

    private CompressibleCrops() {}

    /**
     * 可压缩作物：属于 {@code #compressedagriculture:compressible_crops}，或是 {@code CropBlock} 子类且已成熟。
     * 二者取或（GLOSSARY「可压缩作物」）。
     *
     * <p>本方法只回答「认不认」，成熟与否另由 {@link #isMature} 判断——属于标签但还没长大的作物也是可压缩作物，
     * 只是不该结算。
     */
    public static boolean isCompressibleCrop(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof StemBlock) {
            return false;
        }
        if (state.is(BlockTags.SAPLINGS) || block instanceof CocoaBlock) {
            return false;
        }
        if (state.is(ModTags.Blocks.COMPRESSIBLE_CROPS)) {
            return true;
        }
        return block instanceof CropBlock crop && crop.isMaxAge(state);
    }

    /**
     * 是否已到可收获的成熟态。
     *
     * <p>{@code CropBlock} 用 {@link CropBlock#isMaxAge}；其余（如 {@code pitcher_crop} / {@code torchflower_crop}）
     * 回退到「{@code AGE} 属性取到最大值」。没有 {@code AGE} 属性的一律不算成熟。
     */
    public static boolean isMature(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        IntegerProperty age = ageProperty(state);
        return age != null && state.getValue(age) >= Collections.max(age.getPossibleValues());
    }

    /**
     * 重置幼苗：把作物设回 {@code age = 0}，让同一株继续下一轮。
     *
     * <p>只在结算返回 {@code SUCCESS} 时调用（ADR-0005）。
     */
    public static void resetToSeedling(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            level.setBlock(pos, crop.getStateForAge(0), Block.UPDATE_CLIENTS);
            return;
        }
        IntegerProperty age = ageProperty(state);
        if (age == null) {
            return;
        }
        level.setBlock(pos, state.setValue(age, 0), Block.UPDATE_CLIENTS);
    }

    /** 这个方块状态是否有可重置的年龄属性；没有的话调用方只记 DEBUG。 */
    public static boolean hasAgeProperty(BlockState state) {
        return state.getBlock() instanceof CropBlock || ageProperty(state) != null;
    }

    /**
     * 非 {@code CropBlock} 的年龄属性：按名字 {@code age} 找整数属性。
     *
     * <p>{@code CropBlock.getAgeProperty()} 是 {@code protected}，外部包调不到；{@code CropBlock} 自己走
     * {@code getStateForAge(0)}，所以本方法只需要覆盖「不是 {@code CropBlock} 但带 AGE 的作物」。
     */
    @Nullable
    private static IntegerProperty ageProperty(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty integer && AGE_PROPERTY_NAME.equals(property.getName())) {
                return integer;
            }
        }
        return null;
    }
}
