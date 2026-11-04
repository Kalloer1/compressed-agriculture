package com.kalloer.compressedagriculture.topcut;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.storage.ChestStorage;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 紫颂循环的 GameTest（ticket 14、ADR-0022）。
 *
 * <p><b>驱动方式</b>：{@code ChorusFlowerBlock.randomTick} 是 {@code protected}，GameTest 里调不到；而「长高」在世界里的
 * 唯一表现就是「第 1 格变成 {@code chorus_plant}、头顶出现一朵新的 {@code chorus_flower}」——正是
 * {@code ChorusFlowerBlock.randomTick} 里那两句 {@code setBlock}，也是 {@code BlockBehaviour#onPlace} 的触发路径。
 * 所以这里按同样顺序自己放这两格（底座的状态用 {@link ChorusPlantBlock#getStateWithConnections} 拼，与原版一致），
 * 之后等两 tick 让 {@code ServerTickEvent.Post} 里的处理跑完。
 *
 * <p>产物断言用「9 的倍数」：紫颂花恒掉 1 朵 → {@code 9}；紫颂茎掉 0–1 个紫颂果 → {@code 0} 或 {@code 9}。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class ChorusHarvestGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation CHORUS_FLOWER = BuiltInRegistries.ITEM.getKey(Items.CHORUS_FLOWER);
    private static final ResourceLocation CHORUS_FRUIT = BuiltInRegistries.ITEM.getKey(Items.CHORUS_FRUIT);

    /**
     * 长高 → 整株被拆、底座原位回填 {@code AGE = 0} 的紫颂花、产物乘 {@code 9^1} 进货仓。
     *
     * <p>顶上那朵花故意放成 {@code AGE = 2}：原版长高时新花的年龄沿用底座那朵，所以「回填回到 0」是真实需要
     * 显式 {@code setValue} 的一步，用 0 起手测不出这件事。
     */
    @GameTest(template = TEMPLATE)
    public void harvestsWholeChorusAndRefillsFlower(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(1, 1, 1);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        BlockPos basePos = farmlandPos.above();
        BlockPos flowerPos = basePos.above();
        helper.setBlock(basePos, Blocks.CHORUS_FLOWER);

        ServerLevel level = helper.getLevel();
        BlockPos absoluteBase = helper.absolutePos(basePos);
        BlockPos absoluteFlower = helper.absolutePos(flowerPos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(helper.absolutePos(farmlandPos));
        helper.assertTrue(chest != null, "expected a chest on the compressed farmland");

        helper.runAfterDelay(1, () -> {
            helper.setBlock(basePos, ChorusPlantBlock.getStateWithConnections(level, absoluteBase,
                    Blocks.CHORUS_PLANT.defaultBlockState()));
            helper.setBlock(flowerPos, Blocks.CHORUS_FLOWER.defaultBlockState()
                    .setValue(ChorusFlowerBlock.AGE, 2));
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(level.getBlockState(absoluteFlower).isAir(),
                        "expected the top chorus flower to be harvested, got "
                                + level.getBlockState(absoluteFlower));
                BlockState refilled = level.getBlockState(absoluteBase);
                helper.assertTrue(refilled.is(Blocks.CHORUS_FLOWER),
                        "expected a chorus flower refilled on the base cell, got " + refilled);
                helper.assertTrue(refilled.getValue(ChorusFlowerBlock.AGE) == 0,
                        "expected the refilled flower at AGE 0, got " + refilled.getValue(ChorusFlowerBlock.AGE));

                long flowers = chest.storage().amountOf(CHORUS_FLOWER);
                long fruits = chest.storage().amountOf(CHORUS_FRUIT);
                helper.assertTrue(flowers == 9L,
                        "expected 1 chorus flower x 9^1 = 9 in the chest, got " + flowers);
                helper.assertTrue(fruits == 0L || fruits == 9L,
                        "expected 0 or 9 chorus fruit (uniform 0..1 x 9^1), got " + fruits);
                helper.succeed();
            });
        });
    }

    /**
     * 停摆：货仓塞满 → 整株原样保留（底座还是紫颂茎、顶花还在、不回填）；腾出一格并重新触发一次生长 → 恢复。
     *
     * <p>腾空间后必须重新放一遍第 2 格：{@code onPlace} 是本模块唯一的触发点，停摆期间没有任何东西会再触发它。
     */
    @GameTest(template = TEMPLATE)
    public void stallsWhenChestIsFullThenRecovers(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(3, 1, 3);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        BlockPos basePos = farmlandPos.above();
        BlockPos flowerPos = basePos.above();
        helper.setBlock(basePos, Blocks.CHORUS_FLOWER);

        ServerLevel level = helper.getLevel();
        BlockPos absoluteBase = helper.absolutePos(basePos);
        BlockPos absoluteFlower = helper.absolutePos(flowerPos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(helper.absolutePos(farmlandPos));
        long capacity = chest.storage().capacityPerSlot();
        ResourceLocation filler = BuiltInRegistries.ITEM.getKey(Items.DIRT);
        for (int slot = 0; slot < ChestStorage.SLOT_COUNT; slot++) {
            helper.assertTrue(chest.storage().insert(slot, filler, capacity) == capacity,
                    "expected slot " + slot + " to be filled to " + capacity);
        }

        helper.setBlock(basePos, ChorusPlantBlock.getStateWithConnections(level, absoluteBase,
                Blocks.CHORUS_PLANT.defaultBlockState()));
        helper.setBlock(flowerPos, Blocks.CHORUS_FLOWER);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getBlockState(absoluteBase).is(Blocks.CHORUS_PLANT),
                    "expected the chorus plant to stay while the chest is full, got "
                            + level.getBlockState(absoluteBase));
            helper.assertTrue(level.getBlockState(absoluteFlower).is(Blocks.CHORUS_FLOWER),
                    "expected the top chorus flower to stay while the chest is full, got "
                            + level.getBlockState(absoluteFlower));
            helper.assertTrue(chest.storage().amountOf(CHORUS_FLOWER) == 0L,
                    "expected nothing to be settled into a full chest, got "
                            + chest.storage().amountOf(CHORUS_FLOWER));

            // 腾出**两**格：紫颂花与紫颂果是两个物品，各占一格
            chest.storage().extract(0, capacity);
            chest.storage().extract(1, capacity);
            level.setBlock(absoluteFlower, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.setBlock(flowerPos, Blocks.CHORUS_FLOWER);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(level.getBlockState(absoluteFlower).isAir(),
                        "expected the chorus to be harvested after space was freed, got "
                                + level.getBlockState(absoluteFlower));
                helper.assertTrue(level.getBlockState(absoluteBase).is(Blocks.CHORUS_FLOWER),
                        "expected a chorus flower refilled on the base cell, got "
                                + level.getBlockState(absoluteBase));
                helper.assertTrue(chest.storage().amountOf(CHORUS_FLOWER) == 9L,
                        "expected 9 chorus flower in the chest, got "
                                + chest.storage().amountOf(CHORUS_FLOWER));
                helper.succeed();
            });
        });
    }

    /**
     * 循环继续：回填的那朵花在压缩耕地上还能再长高，长高后又被整株收走。
     *
     * <p>驱动方式：{@code ChorusFlowerBlock.randomTick} 是 {@code protected}，但 {@code BlockStateBase#randomTick}
     * 是 public，直接调一次即可。压缩耕地上紫颂的竖向生长路径没有随机门槛——{@code flag} 来自
     * {@code canSustainPlant}（ADR-0020 返回 TRUE），所以一次随机刻必定长高。
     */
    @GameTest(template = TEMPLATE)
    public void refilledFlowerGrowsAgain(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(9, 1, 9);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        BlockPos basePos = farmlandPos.above();
        BlockPos flowerPos = basePos.above();
        helper.setBlock(basePos, Blocks.CHORUS_FLOWER);

        ServerLevel level = helper.getLevel();
        BlockPos absoluteBase = helper.absolutePos(basePos);
        BlockPos absoluteFlower = helper.absolutePos(flowerPos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(helper.absolutePos(farmlandPos));

        helper.runAfterDelay(1, () -> {
            helper.setBlock(basePos, ChorusPlantBlock.getStateWithConnections(level, absoluteBase,
                    Blocks.CHORUS_PLANT.defaultBlockState()));
            helper.setBlock(flowerPos, Blocks.CHORUS_FLOWER);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(chest.storage().amountOf(CHORUS_FLOWER) == 9L,
                        "expected the first harvest to settle 9 chorus flower, got "
                                + chest.storage().amountOf(CHORUS_FLOWER));
                BlockState refilled = level.getBlockState(absoluteBase);
                helper.assertTrue(refilled.is(Blocks.CHORUS_FLOWER),
                        "expected a chorus flower refilled on the base cell, got " + refilled);

                refilled.randomTick(level, absoluteBase, level.random);
                helper.runAfterDelay(2, () -> {
                    helper.assertTrue(level.getBlockState(absoluteFlower).isAir(),
                            "expected the second growth to be harvested too, got "
                                    + level.getBlockState(absoluteFlower));
                    helper.assertTrue(level.getBlockState(absoluteBase).is(Blocks.CHORUS_FLOWER),
                            "expected another chorus flower refilled on the base cell, got "
                                    + level.getBlockState(absoluteBase));
                    long flowers = chest.storage().amountOf(CHORUS_FLOWER);
                    helper.assertTrue(flowers == 18L,
                            "expected two rounds of 9 chorus flower = 18, got " + flowers);
                    helper.succeed();
                });
            });
        });
    }

    /** 反例：普通末地石上的紫颂长高 → 不被拆（紫颂循环只对压缩耕地生效）。 */
    @GameTest(template = TEMPLATE)
    public void keepsChorusOnPlainEndStone(GameTestHelper helper) {
        BlockPos endStonePos = new BlockPos(5, 1, 5);
        helper.setBlock(endStonePos, Blocks.END_STONE);
        BlockPos basePos = endStonePos.above();
        BlockPos flowerPos = basePos.above();
        ServerLevel level = helper.getLevel();
        BlockPos absoluteBase = helper.absolutePos(basePos);
        BlockPos absoluteFlower = helper.absolutePos(flowerPos);

        helper.setBlock(basePos, ChorusPlantBlock.getStateWithConnections(level, absoluteBase,
                Blocks.CHORUS_PLANT.defaultBlockState()));
        helper.setBlock(flowerPos, Blocks.CHORUS_FLOWER);

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getBlockState(absoluteBase).is(Blocks.CHORUS_PLANT),
                    "expected the chorus plant on plain end stone to stay, got "
                            + level.getBlockState(absoluteBase));
            helper.assertTrue(level.getBlockState(absoluteFlower).is(Blocks.CHORUS_FLOWER),
                    "expected the chorus flower on plain end stone to stay, got "
                            + level.getBlockState(absoluteFlower));
            helper.succeed();
        });
    }

    /** 反例：压缩耕地上只放一朵紫颂花（还没长高，下面没有紫颂茎）→ 不触发。 */
    @GameTest(template = TEMPLATE)
    public void keepsLoneFlowerBeforeItGrows(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(7, 1, 7);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        BlockPos flowerPos = farmlandPos.above();
        helper.setBlock(flowerPos, Blocks.CHORUS_FLOWER);

        ServerLevel level = helper.getLevel();
        BlockPos absoluteFlower = helper.absolutePos(flowerPos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(helper.absolutePos(farmlandPos));

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getBlockState(absoluteFlower).is(Blocks.CHORUS_FLOWER),
                    "expected a lone chorus flower to stay, got " + level.getBlockState(absoluteFlower));
            helper.assertTrue(chest.storage().amountOf(CHORUS_FLOWER) == 0L,
                    "expected nothing to be settled, got " + chest.storage().amountOf(CHORUS_FLOWER));
            helper.succeed();
        });
    }
}
