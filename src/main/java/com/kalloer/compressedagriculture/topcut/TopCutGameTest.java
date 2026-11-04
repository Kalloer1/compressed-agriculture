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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 多格植物截顶的 GameTest（ticket 13、ADR-0021）。
 *
 * <p><b>驱动方式</b>：甘蔗的 {@code randomTick} 是 {@code protected}，GameTest 里调不到，而「长到第 2 格」在世界里的
 * 唯一表现就是「头顶那一格被放进了一个同种方块」——也就是 {@code LevelChunk.setBlockState} 调
 * {@code BlockBehaviour#onPlace}。所以这里一律用 {@code helper.setBlock} 造出第 2 格，走的正是真实生长那条路径
 * （ticket 13 明确允许）。之后等两 tick，让 {@code ServerTickEvent.Post} 里的处理跑完。
 *
 * <p>每条用例都断言「第 1 格还在」：截顶只拆第 2 格，第 1 格要留下来继续下一轮。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class TopCutGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation SUGAR_CANE = BuiltInRegistries.ITEM.getKey(Items.SUGAR_CANE);

    /**
     * 随机刻长高：第 1 格先存在，一 tick 之后第 2 格出现 → 第 2 格被拆、产物 1 × {@code 9^1} 进货仓、第 1 格不动。
     *
     * <p>货仓里是 {@code 9} 而不是「9 的倍数」：甘蔗的战利品表恒掉 1 个自身，没有随机量。
     */
    @GameTest(template = TEMPLATE)
    public void cutsSecondSugarCaneAndSettlesIntoFarmland(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(1, 1, 1);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        BlockPos firstPos = farmlandPos.above();
        helper.setBlock(firstPos, Blocks.SUGAR_CANE);
        BlockPos secondPos = firstPos.above();

        ServerLevel level = helper.getLevel();
        BlockPos absoluteFirst = helper.absolutePos(firstPos);
        BlockPos absoluteSecond = helper.absolutePos(secondPos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(helper.absolutePos(farmlandPos));
        helper.assertTrue(chest != null, "expected a chest on the compressed farmland");

        helper.runAfterDelay(1, () -> {
            helper.setBlock(secondPos, Blocks.SUGAR_CANE);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(level.getBlockState(absoluteSecond).isAir(),
                        "expected the second sugar cane to be cut, got " + level.getBlockState(absoluteSecond));
                helper.assertTrue(level.getBlockState(absoluteFirst).is(Blocks.SUGAR_CANE),
                        "expected the first sugar cane to stay, got " + level.getBlockState(absoluteFirst));
                long settled = chest.storage().amountOf(SUGAR_CANE);
                helper.assertTrue(settled == 9L,
                        "expected 1 sugar cane x 9^1 = 9 in the chest, got " + settled);
                helper.succeed();
            });
        });
    }

    /**
     * 玩家手动往上堆第 2 格：同样被拆走并结算。这是 ticket 13 明确接受的副作用——「这块地上只保留 1 格」。
     */
    @GameTest(template = TEMPLATE)
    public void cutsManuallyStackedSecondSugarCane(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(2, 1, 2);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        BlockPos firstPos = farmlandPos.above();
        BlockPos secondPos = firstPos.above();
        helper.setBlock(firstPos, Blocks.SUGAR_CANE);
        helper.setBlock(secondPos, Blocks.SUGAR_CANE);

        ServerLevel level = helper.getLevel();
        BlockPos absoluteFirst = helper.absolutePos(firstPos);
        BlockPos absoluteSecond = helper.absolutePos(secondPos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(helper.absolutePos(farmlandPos));

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getBlockState(absoluteSecond).isAir(),
                    "expected a manually stacked second sugar cane to be cut, got "
                            + level.getBlockState(absoluteSecond));
            helper.assertTrue(level.getBlockState(absoluteFirst).is(Blocks.SUGAR_CANE),
                    "expected the first sugar cane to stay, got " + level.getBlockState(absoluteFirst));
            long settled = chest.storage().amountOf(SUGAR_CANE);
            helper.assertTrue(settled == 9L, "expected 9 sugar cane in the chest, got " + settled);
            helper.succeed();
        });
    }

    /**
     * 停摆：货仓塞满 → 第 2 格原样保留、货仓一个物品都没变；腾出一格并让第 2 格重新出现（{@code onPlace} 是本模块唯一的
     * 触发点）→ 截顶恢复，产物落进货仓。
     *
     * <p>腾空间之后必须重新触发一次 {@code onPlace}：第 2 格已经占着头顶那一格，甘蔗再也长不出新的第 2 格，
     * 所以「等下一次生长」在停摆期间不会发生。这里用「拆掉再放回」模拟玩家自己动手。
     */
    @GameTest(template = TEMPLATE)
    public void stallsWhenChestIsFullThenRecovers(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(3, 1, 3);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        BlockPos firstPos = farmlandPos.above();
        helper.setBlock(firstPos, Blocks.SUGAR_CANE);
        BlockPos secondPos = firstPos.above();

        ServerLevel level = helper.getLevel();
        BlockPos absoluteSecond = helper.absolutePos(secondPos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(helper.absolutePos(farmlandPos));
        long capacity = chest.storage().capacityPerSlot();
        ResourceLocation filler = BuiltInRegistries.ITEM.getKey(Items.DIRT);
        for (int slot = 0; slot < ChestStorage.SLOT_COUNT; slot++) {
            helper.assertTrue(chest.storage().insert(slot, filler, capacity) == capacity,
                    "expected slot " + slot + " to be filled to " + capacity);
        }

        helper.setBlock(secondPos, Blocks.SUGAR_CANE);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getBlockState(absoluteSecond).is(Blocks.SUGAR_CANE),
                    "expected the second sugar cane to stay while the chest is full, got "
                            + level.getBlockState(absoluteSecond));
            helper.assertTrue(chest.storage().amountOf(SUGAR_CANE) == 0L,
                    "expected nothing to be settled into a full chest, got "
                            + chest.storage().amountOf(SUGAR_CANE));

            chest.storage().extract(0, capacity);
            level.setBlock(absoluteSecond, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.setBlock(secondPos, Blocks.SUGAR_CANE);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(level.getBlockState(absoluteSecond).isAir(),
                        "expected the second sugar cane to be cut after space was freed, got "
                                + level.getBlockState(absoluteSecond));
                long settled = chest.storage().amountOf(SUGAR_CANE);
                helper.assertTrue(settled == 9L, "expected 9 sugar cane in the chest, got " + settled);
                helper.succeed();
            });
        });
    }

    /**
     * 反例：普通沙子上的甘蔗长到第 2 格 → 不被拆（截顶只对压缩耕地生效）。
     *
     * <p>沙子旁边那格水是必须的：原版甘蔗 {@code canSurvive} 在沙子上要求水平相邻有水，没有水的话邻居更新会把它
     * 自己弹掉，测出来的「第 2 格不见了」就不是截顶干的。
     */
    @GameTest(template = TEMPLATE)
    public void keepsSecondBlockOnPlainSand(GameTestHelper helper) {
        BlockPos sandPos = new BlockPos(5, 1, 5);
        helper.setBlock(sandPos, Blocks.SAND);
        helper.setBlock(sandPos.east(), Blocks.WATER);
        BlockPos firstPos = sandPos.above();
        helper.setBlock(firstPos, Blocks.SUGAR_CANE);
        BlockPos secondPos = firstPos.above();
        helper.setBlock(secondPos, Blocks.SUGAR_CANE);

        ServerLevel level = helper.getLevel();
        BlockPos absoluteSecond = helper.absolutePos(secondPos);

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getBlockState(absoluteSecond).is(Blocks.SUGAR_CANE),
                    "expected the second sugar cane on plain sand to stay, got "
                            + level.getBlockState(absoluteSecond));
            helper.succeed();
        });
    }

    /** 反例：压缩耕地上第 2 格放的是**不同**方块（火把）→ 不是多格植物，不拆。 */
    @GameTest(template = TEMPLATE)
    public void keepsDifferentBlockAbovePlant(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(7, 1, 7);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        BlockPos firstPos = farmlandPos.above();
        helper.setBlock(firstPos, Blocks.SUGAR_CANE);
        BlockPos secondPos = firstPos.above();
        helper.setBlock(secondPos, Blocks.TORCH);

        ServerLevel level = helper.getLevel();
        BlockPos absoluteSecond = helper.absolutePos(secondPos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(helper.absolutePos(farmlandPos));

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getBlockState(absoluteSecond).is(Blocks.TORCH),
                    "expected the torch above the sugar cane to stay, got "
                            + level.getBlockState(absoluteSecond));
            helper.assertTrue(chest.storage().amountOf(SUGAR_CANE) == 0L,
                    "expected nothing to be settled, got " + chest.storage().amountOf(SUGAR_CANE));
            helper.succeed();
        });
    }
}
