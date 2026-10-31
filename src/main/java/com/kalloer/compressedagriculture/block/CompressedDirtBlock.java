package com.kalloer.compressedagriculture.block;

import net.minecraft.world.level.block.state.BlockBehaviour;

/** 压缩泥土：一种形态、一个等级，其余行为来自 {@link CompressedBlock}。 */
public class CompressedDirtBlock extends CompressedBlock {
    public CompressedDirtBlock(int level, BlockBehaviour.Properties properties) {
        super(level, properties);
    }

    @Override
    public CompressedForm form() {
        return CompressedForm.DIRT;
    }
}
