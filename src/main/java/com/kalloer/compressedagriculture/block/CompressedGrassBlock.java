package com.kalloer.compressedagriculture.block;

import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.storage.CompressedChestTransfer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * 压缩草地。
 *
 * <p>不继承 {@code GrassBlock} / {@code SpreadingSnowyDirtBlock}：原版的草蔓延只认 {@code Blocks.DIRT} 这一个身份
 * （ADR-0018 已核对），对压缩方块无效；压缩方块之间的蔓延由本类在 {@code randomTick} 里实现。
 */
public class CompressedGrassBlock extends CompressedBlock {
    /** 每次随机刻最多尝试蔓延的次数，与原版 {@code SpreadingSnowyDirtBlock} 一致。 */
    private static final int SPREAD_ATTEMPTS = 4;
    /** 蔓延所需的最低亮度（原版硬编码 9）。 */
    private static final int MIN_SPREAD_LIGHT = 9;

    public CompressedGrassBlock(int level, BlockBehaviour.Properties properties) {
        super(level, properties);
    }

    @Override
    public CompressedForm form() {
        return CompressedForm.GRASS;
    }

    /**
     * 草蔓延（ticket 09、ADR-0018）：向<b>同等级</b>压缩泥土蔓延成同等级压缩草地，每次最多 4 次尝试。
     *
     * <p>条件照抄原版 {@code SpreadingSnowyDirtBlock.randomTick}：亮度 ≥ 9；目标上方能长草（不是整块流体、不透光）；
     * 目标上方不被水覆盖。取样范围也照抄原版 {@code (nextInt(3)-1, nextInt(5)-3, nextInt(3)-1)}——水平 8 邻格、
     * 向上 1 格、向下最多 3 格。
     *
     * <p>换方块必须走 {@link CompressedChestTransfer#convert}：被覆盖那一格的货仓要原样保留（ADR-0002）。
     * 原版那种 {@code setBlockAndUpdate} 会把 27 格数据一起清掉。
     *
     * <p>不实现原版的「长不成草就变泥土」分支：那等于把方块换掉，本票没要求，多一条丢数据的路径不值得。
     * 但「长不成草就不蔓延」这一半保留了（原版 {@code canBeGrass}），所以被压住的草地不会往下蔓延。
     */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!canBeGrass(state, level, pos)) {
            return;
        }
        if (level.getMaxLocalRawBrightness(pos.above()) < MIN_SPREAD_LIGHT) {
            return;
        }
        BlockState grassState = this.defaultBlockState();
        for (int attempt = 0; attempt < SPREAD_ATTEMPTS; attempt++) {
            BlockPos target = pos.offset(random.nextInt(3) - 1, random.nextInt(5) - 3, random.nextInt(3) - 1);
            BlockState targetState = level.getBlockState(target);
            if (!(targetState.getBlock() instanceof CompressedBlock targetBlock)
                    || targetBlock.form() != CompressedForm.DIRT
                    || targetBlock.level() != level()
                    || !canPropagate(grassState, level, target)) {
                continue;
            }
            BlockState spread = ModBlocks.block(CompressedForm.GRASS, level()).defaultBlockState();
            CompressedChestTransfer.convert(level, target, spread);
        }
    }

    /** 原版 {@code canPropagate}：目标上方能长草，且不被水覆盖。 */
    private static boolean canPropagate(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos above = pos.above();
        return canBeGrass(state, level, pos) && !level.getFluidState(above).is(FluidTags.WATER);
    }

    /** 原版 {@code canBeGrass}：上方是 1 层雪、或上方不透满光。 */
    private static boolean canBeGrass(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos above = pos.above();
        BlockState aboveState = level.getBlockState(above);
        if (aboveState.is(Blocks.SNOW) && aboveState.getValue(SnowLayerBlock.LAYERS) == 1) {
            return true;
        }
        if (aboveState.getFluidState().getAmount() == 8) {
            return false;
        }
        int lightBlock = LightEngine.getLightBlockInto(level, state, pos, aboveState, above, Direction.UP,
                aboveState.getLightBlock(level, above));
        return lightBlock < level.getMaxLightLevel();
    }
}
