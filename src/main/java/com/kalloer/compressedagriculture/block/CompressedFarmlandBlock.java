package com.kalloer.compressedagriculture.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 压缩耕地。
 *
 * <p><b>不继承 {@code FarmBlock}</b>：它的 {@code randomTick} / {@code tick} / {@code fallOn} 都会把方块换成原版泥土，
 * 货仓随之丢失。水合、踩踏、锄头转换都由 ticket 09 在本类（或其子逻辑）里实现。
 *
 * <p>但 {@code MOISTURE} 属性必须现在就有：ticket 04 往 {@code CropBlock.getGrowthSpeed} 注入后，压缩耕地一旦被当成
 * 耕地就会立刻 {@code getValue(FarmBlock.MOISTURE)}，缺属性直接 {@code IllegalStateException}（ADR-0018）。
 * 这里用的就是 {@link BlockStateProperties#MOISTURE} 本身，与 {@code FarmBlock.MOISTURE} 是同一个实例。
 */
public class CompressedFarmlandBlock extends CompressedBlock {
    /** 与 {@code FarmBlock.MOISTURE} 同一个属性实例，默认 0。水合留给 ticket 09。 */
    public static final IntegerProperty MOISTURE = BlockStateProperties.MOISTURE;

    /**
     * 原版耕地的形状：16×15×16。方块模型复用 {@code minecraft:block/farmland}（同样矮 1/16），
     * 碰撞盒必须跟上，否则玩家会浮在模型上方 1/16。
     */
    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 15.0, 16.0);

    public CompressedFarmlandBlock(int level, BlockBehaviour.Properties properties) {
        super(level, properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(MOISTURE, 0));
    }

    @Override
    public CompressedForm form() {
        return CompressedForm.FARMLAND;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MOISTURE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
