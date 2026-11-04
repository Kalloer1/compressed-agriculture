package com.kalloer.compressedagriculture.block;

import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.registry.ModTags;
import com.kalloer.compressedagriculture.storage.CompressedChestTransfer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.util.TriState;

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
    /** 与 {@code FarmBlock.MOISTURE} 同一个属性实例，默认 0。 */
    public static final IntegerProperty MOISTURE = BlockStateProperties.MOISTURE;
    /** 与 {@code FarmBlock.MAX_MOISTURE} 相同的上限。 */
    public static final int MAX_MOISTURE = 7;

    /**
     * 找水的水平半径：以耕地为中心的 9×3×9 区域（水平 ±4、上下各 1 层）。
     *
     * <p>原版 {@code FarmBlock.isNearWater} 只扫 {@code y ∈ [0, +1]}，票（ticket 09）要求「含上下各 1 层」，所以这里向下
     * 多扫一层——只放宽、不收紧，原版能水合的情形这里同样水合。
     */
    private static final int WATER_RADIUS = 4;

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

    /**
     * 通用地基（ADR-0020）：除了禁种表里的植物，任何植物都能在压缩耕地上活。
     *
     * <p>这是「通用地基」唯一的一处实现点。{@code Block} 已经 implements 了
     * {@code IBlockExtension}，所以直接 override，不需要任何 mixin。原版植物的
     * {@code canSurvive} 第一步都会问脚下这块地，拿到非 {@code DEFAULT} 就采纳、后面整段硬编码检查跳过：
     * {@code CropBlock} / {@code BushBlock} 各在 {@code canSurvive} 第一行；{@code SugarCaneBlock}、
     * {@code CactusBlock}（侧面检查之后）、{@code MushroomBlock}（{@code #mushroom_grow_block} 之后）、
     * {@code BambooStalkBlock}、{@code ChorusFlowerBlock} 同理。
     *
     * <p><b>两个已知且已接受的副作用</b>：压缩耕地上的甘蔗<b>不需要临水</b>（原版要求下方是 {@code #dirt}/{@code #sand}
     * 且水平相邻有水）；所有 {@code CropBlock} 在压缩耕地上<b>不因光照不足而弹掉</b>（原版要求光照 ≥ 8）。
     * 注意「不弹掉」不等于「能长大」：{@code CropBlock.randomTick} 里还有一道独立的「光照 ≥ 9 才生长」检查，
     * 它不在 {@code canSurvive} 里，所以黑暗地窖里的作物会一直停在幼苗。
     *
     * <p><b>不受影响的原版约束</b>：仙人掌的侧面检查（四邻不能是实心方块、不能有岩浆）发生在
     * {@code canSustainPlant} 之前，所以旁边贴着实心方块时仙人掌照样弹掉。
     *
     * <p><b>禁种表</b>（{@code #compressedagriculture:not_plantable_on_compressed_farmland}，默认空）：命中的植物
     * 方块返回 {@link TriState#DEFAULT}，交回原版判断。某个模组植物在这里行为崩坏时的逃生口。
     *
     * <p>注意 {@code #minecraft:dirt} 标签<b>仍然必须保留</b>：瓜类果实的落位判断是
     * {@code blockstate.getBlock() instanceof FarmBlock || blockstate.is(BlockTags.DIRT)}（{@code StemBlock.randomTick}），
     * 不读本方法。
     */
    @Override
    public TriState canSustainPlant(BlockState state, BlockGetter level, BlockPos soilPosition, Direction facing,
            BlockState plant) {
        if (plant.is(ModTags.Blocks.NOT_PLANTABLE_ON_COMPRESSED_FARMLAND)) {
            return TriState.DEFAULT;
        }
        return TriState.TRUE;
    }

    /**
     * 水合（ticket 09、ADR-0018）：周围有水或上方降雨 → {@code MOISTURE = 7}；否则每次随机刻递减 1。
     *
     * <p>与 {@code FarmBlock.randomTick} 的唯一区别是「{@code MOISTURE} 降到 0 就停住」：原版此时会把耕地换回泥土，
     * 那会连货仓一起丢掉（ADR-0002）。本类只改 {@code MOISTURE}，不碰 BlockEntity。
     *
     * <p>写方块用 {@code Block.UPDATE_CLIENTS}（只同步客户端），与原版一致；这个 flag 不动 BlockEntity，
     * 所以 27 格货仓不受影响。
     */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        int moisture = state.getValue(MOISTURE);
        if (isNearWater(level, pos) || level.isRainingAt(pos.above())) {
            if (moisture < MAX_MOISTURE) {
                level.setBlock(pos, state.setValue(MOISTURE, MAX_MOISTURE), Block.UPDATE_CLIENTS);
            }
        } else if (moisture > 0) {
            level.setBlock(pos, state.setValue(MOISTURE, moisture - 1), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * 踩踏（ticket 09、ADR-0018）：满足原版条件时变成<b>同等级压缩泥土</b>，货仓原样保留。
     *
     * <p>原版走 {@code FarmBlock.turnToDirt}，那会 {@code setBlockAndUpdate} 成 {@code Blocks.DIRT}——货仓直接丢。
     * 这里改走 {@link CompressedChestTransfer#convert}，与形态互转共用同一套搬运（ADR-0002）。
     */
    @Override
    public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, float fallDistance) {
        if (level instanceof ServerLevel serverLevel && tramples(level, entity, fallDistance)) {
            BlockState dirt = ModBlocks.block(CompressedForm.DIRT, level()).defaultBlockState();
            CompressedChestTransfer.convert(serverLevel, pos, dirt);
        }
        super.fallOn(level, state, pos, entity, fallDistance);
    }

    /**
     * 原版 {@code FarmBlock.fallOn} 的判据：随机过 {@code fallDistance - 0.5}、是活体、玩家或开了生物破坏、
     * 实体底面积 × 高度 &gt; 0.512。额外加一条「潜行不踩踏」——玩家不想踩坏自己的地。
     */
    private static boolean tramples(Level level, Entity entity, float fallDistance) {
        return level.random.nextFloat() < fallDistance - 0.5F
                && entity instanceof LivingEntity living
                && !living.isShiftKeyDown()
                && (living instanceof Player || level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING))
                && living.getBbWidth() * living.getBbWidth() * living.getBbHeight() > 0.512F;
    }

    /** 同原版 {@code FarmBlock.isNearWater}，只是多扫一层下方（见 {@link #WATER_RADIUS}）。 */
    private static boolean isNearWater(LevelReader level, BlockPos pos) {
        BlockPos min = pos.offset(-WATER_RADIUS, -1, -WATER_RADIUS);
        BlockPos max = pos.offset(WATER_RADIUS, 1, WATER_RADIUS);
        for (BlockPos probe : BlockPos.betweenClosed(min, max)) {
            if (level.getFluidState(probe).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }
}
