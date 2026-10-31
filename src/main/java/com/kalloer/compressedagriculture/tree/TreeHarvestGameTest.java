package com.kalloer.compressedagriculture.tree;

import java.util.List;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.settle.SettleResult;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@link TreeHarvest} 的 GameTest（ticket 05b）：拆树 → 写货仓 → 回填树苗。
 *
 * <p>树是手搭的，所以掉落可预测：3 根橡木原木、没有树叶 → 恰好 3 个橡木原木的掉落，乘 {@code 9^1} 就是 27。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class TreeHarvestGameTest {
    private static final String TEMPLATE = "empty";

    /** 一棵 3 格高的树长在 1 级压缩泥土上 → 方块变空气、树苗回填且 STAGE=0、货仓 27 个橡木原木。 */
    @GameTest(template = TEMPLATE)
    public void harvestsTreeIntoCompressedBlockAndRefillsSapling(GameTestHelper helper) {
        BlockPos soilPos = new BlockPos(4, 1, 4);
        helper.setBlock(soilPos, ModBlocks.COMPRESSED_DIRT.get(0).get());
        BlockPos saplingPos = soilPos.above();
        for (int dy = 0; dy <= 2; dy++) {
            helper.setBlock(saplingPos.above(dy), Blocks.OAK_LOG);
        }

        ServerLevel level = helper.getLevel();
        BlockPos absoluteSoil = helper.absolutePos(soilPos);
        BlockPos absoluteSapling = helper.absolutePos(saplingPos);
        helper.assertTrue(level.getBlockEntity(absoluteSoil) instanceof CompressedBlockEntity,
                "expected a chest in the compressed block at " + absoluteSoil);

        // STAGE=1 是故意传的：回填必须把它压回 0，否则下一轮会立刻再长（ADR-0014）
        BlockState refillState = Blocks.OAK_SAPLING.defaultBlockState().setValue(SaplingBlock.STAGE, 1);
        SettleResult result = TreeHarvest.harvest(level, absoluteSapling, absoluteSoil,
                List.of(absoluteSapling), refillState);
        helper.assertTrue(result == SettleResult.SUCCESS, "expected SUCCESS, got " + result);

        // 回填落在树苗原位，所以只检查它上方两格变空气
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos at = absoluteSapling.above(dy);
            helper.assertTrue(level.getBlockState(at).isAir(),
                    "expected air at " + at + ", got " + level.getBlockState(at));
        }

        BlockState refilled = level.getBlockState(absoluteSapling);
        helper.assertTrue(refilled.is(Blocks.OAK_SAPLING), "expected a sapling refilled, got " + refilled);
        helper.assertTrue(refilled.getValue(SaplingBlock.STAGE) == 0,
                "refilled sapling must be STAGE=0, got " + refilled.getValue(SaplingBlock.STAGE));

        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absoluteSoil);
        long logs = chest.storage().amountOf(BuiltInRegistries.ITEM.getKey(Items.OAK_LOG));
        helper.assertTrue(logs == 27L, "expected 3 logs x 9^1 = 27 oak logs in the chest, got " + logs);
        helper.succeed();
    }
}
