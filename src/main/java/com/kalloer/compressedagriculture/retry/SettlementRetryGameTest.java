package com.kalloer.compressedagriculture.retry;

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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 结算重试的 GameTest（ticket 08b）。
 *
 * <p>直接调 {@link SettlementRetry#retryAbove}：三处事件只是「什么时候调它」，事件本身由游戏触发，不在 GameTest 里伪造。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class SettlementRetryGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation WHEAT = BuiltInRegistries.ITEM.getKey(Items.WHEAT);

    /** 成熟小麦 + 有空位的货仓 → 补收 {@code 9^L} 份进仓，幼苗退回 0 龄。 */
    @GameTest(template = TEMPLATE)
    public void retrySettlesMatureCrop(GameTestHelper helper) {
        BlockPos chestPos = new BlockPos(1, 1, 1);
        helper.setBlock(chestPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        helper.setBlock(chestPos.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE));

        ServerLevel level = helper.getLevel();
        BlockPos absoluteChest = helper.absolutePos(chestPos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absoluteChest);
        helper.assertTrue(chest != null, "expected a chest on the compressed farmland");

        SettlementRetry.retryAbove(level, absoluteChest);

        helper.assertTrue(chest.storage().amountOf(WHEAT) == 9L,
                "expected 9 wheat (9^1) in the chest, got " + chest.storage().amountOf(WHEAT));
        int age = level.getBlockState(absoluteChest.above()).getValue(CropBlock.AGE);
        helper.assertTrue(age == 0, "expected the crop to be reset to a seedling, got AGE " + age);
        helper.succeed();
    }

    /** 已经结算过的不再重复结算：第二次 {@code retryAbove} 一份都不加。 */
    @GameTest(template = TEMPLATE)
    public void retryDoesNotSettleTwice(GameTestHelper helper) {
        BlockPos chestPos = new BlockPos(2, 1, 2);
        helper.setBlock(chestPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        helper.setBlock(chestPos.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE));

        ServerLevel level = helper.getLevel();
        BlockPos absoluteChest = helper.absolutePos(chestPos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absoluteChest);

        SettlementRetry.retryAbove(level, absoluteChest);
        long afterFirst = chest.storage().amountOf(WHEAT);
        SettlementRetry.retryAbove(level, absoluteChest);
        helper.assertTrue(chest.storage().amountOf(WHEAT) == afterFirst,
                "expected no extra wheat on the second retry, was " + afterFirst
                        + " and became " + chest.storage().amountOf(WHEAT));
        helper.succeed();
    }

    /** 没成熟的小麦不动：重试只处理「成熟且尚未结算」的方块。 */
    @GameTest(template = TEMPLATE)
    public void retrySkipsImmatureCrop(GameTestHelper helper) {
        BlockPos chestPos = new BlockPos(3, 1, 3);
        helper.setBlock(chestPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        helper.setBlock(chestPos.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 3));

        ServerLevel level = helper.getLevel();
        BlockPos absoluteChest = helper.absolutePos(chestPos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absoluteChest);

        SettlementRetry.retryAbove(level, absoluteChest);

        helper.assertTrue(chest.storage().usedSlots() == 0,
                "expected an empty chest, got " + chest.storage().usedSlots() + " used slots");
        int age = level.getBlockState(absoluteChest.above()).getValue(CropBlock.AGE);
        helper.assertTrue(age == 3, "expected the crop to be untouched, got AGE " + age);
        helper.succeed();
    }

    /** 货仓放不下时保持成熟态（ADR-0005）：一格上限 64，先占满 27 格，重试不写入、不重置。 */
    @GameTest(template = TEMPLATE)
    public void retryKeepsMaturityWhenChestIsFull(GameTestHelper helper) {
        BlockPos chestPos = new BlockPos(4, 1, 4);
        helper.setBlock(chestPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        helper.setBlock(chestPos.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE));

        ServerLevel level = helper.getLevel();
        BlockPos absoluteChest = helper.absolutePos(chestPos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absoluteChest);
        for (int slot = 0; slot < ChestStorage.SLOT_COUNT; slot++) {
            // 每格塞满 64 个苹果：27 格全满，小麦与种子都没地方放
            chest.storage().tryReserve(BuiltInRegistries.ITEM.getKey(Items.APPLE), 64L);
            chest.storage().commit();
        }

        SettlementRetry.retryAbove(level, absoluteChest);

        helper.assertTrue(chest.storage().amountOf(WHEAT) == 0L,
                "expected nothing written into a full chest, got " + chest.storage().amountOf(WHEAT));
        int age = level.getBlockState(absoluteChest.above()).getValue(CropBlock.AGE);
        helper.assertTrue(age == CropBlock.MAX_AGE,
                "expected the mature crop to stay mature, got AGE " + age);
        helper.succeed();
    }
}
