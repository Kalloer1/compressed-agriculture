package com.kalloer.compressedagriculture.block;

import java.util.OptionalInt;

import javax.annotation.Nullable;

import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 27 个压缩方块的公共基类：只携带等级，形态由子类给出。
 *
 * <p>等级与形态都编码在方块（Block）里，不进 BlockEntity——同一个 {@code BlockEntityType} 覆盖全部 27 个方块，
 * BlockEntity 里只有货仓。
 *
 * <p>不继承 {@code FarmBlock}：它的 {@code randomTick} / {@code tick} / {@code fallOn} 会调 {@code turnToDirt}
 * 把方块换成原版泥土，货仓直接丢。压缩耕地的水合 / 踩踏由 ticket 09 在自己的类里实现（ADR-0018）。
 */
public abstract class CompressedBlock extends Block implements EntityBlock {
    /** 等级（合成深度）下界。 */
    public static final int MIN_LEVEL = 1;
    /** 等级（合成深度）上界，同时也是每形态的方块个数。 */
    public static final int MAX_LEVEL = 9;

    private final int level;

    protected CompressedBlock(int level, BlockBehaviour.Properties properties) {
        super(properties);
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "level must be within [" + MIN_LEVEL + ", " + MAX_LEVEL + "], but was " + level);
        }
        this.level = level;
    }

    /** 合成深度 1..9，决定收获倍率 {@code 9^L}（ADR-0003）。 */
    public int level() {
        return this.level;
    }

    /** 本方块属于哪种形态。 */
    public abstract CompressedForm form();

    /**
     * 等级读取出口，ticket 05 / 07 依赖它从「某个位置 / 某个方块状态」读出 {@code L}。
     *
     * <p>只读 {@link BlockState}，不读 BlockEntity、不读世界。非压缩方块与 {@code null} 都返回
     * {@link OptionalInt#empty()}，不抛异常。
     */
    public static OptionalInt levelOf(@Nullable BlockState state) {
        if (state != null && state.getBlock() instanceof CompressedBlock compressedBlock) {
            return OptionalInt.of(compressedBlock.level());
        }
        return OptionalInt.empty();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompressedBlockEntity(pos, state);
    }
}
