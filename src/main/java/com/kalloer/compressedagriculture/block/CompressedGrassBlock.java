package com.kalloer.compressedagriculture.block;

import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 压缩草地。
 *
 * <p>不继承 {@code GrassBlock} / {@code SpreadingSnowyDirtBlock}：原版的草蔓延只认 {@code Blocks.DIRT} 这一个身份
 * （ADR-0018 已核对），对压缩方块无效；压缩方块之间的蔓延由 ticket 09 实现。
 */
public class CompressedGrassBlock extends CompressedBlock {
    public CompressedGrassBlock(int level, BlockBehaviour.Properties properties) {
        super(level, properties);
    }

    @Override
    public CompressedForm form() {
        return CompressedForm.GRASS;
    }
}
