package com.kalloer.compressedagriculture.mushroom;

import java.util.List;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.settle.SettleResult;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;
import com.kalloer.compressedagriculture.tree.TreeHarvest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 蘑菇树拆除的 GameTest（ticket 06）：蘑菇模块没有自己的拆除引擎，直接复用 {@link TreeHarvest}，
 * 所以这里验证的正是路径一（骨粉催蘑菇树）在「蘑菇树已经长好」之后的全部行为。
 *
 * <p>蘑菇树是手搭的，但蘑菇块的掉落是 0–2 个随机数，所以只能断言「货仓里只有红蘑菇、数量是 9 的整数倍、且非空」。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class MushroomHarvestGameTest {
    private static final String TEMPLATE = "empty";

    /** 红菇树长在 1 级压缩泥土上 → 整棵变空气、原位回填一个红蘑菇、货仓里是 9 的倍数的红蘑菇。 */
    @GameTest(template = TEMPLATE)
    public void harvestsMushroomTreeIntoCompressedBlockAndRefillsMushroom(GameTestHelper helper) {
        BlockPos soilPos = new BlockPos(4, 1, 4);
        helper.setBlock(soilPos, ModBlocks.COMPRESSED_DIRT.get(0).get());
        BlockPos base = soilPos.above();
        helper.setBlock(base, Blocks.MUSHROOM_STEM);
        helper.setBlock(base.above(), Blocks.MUSHROOM_STEM);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                helper.setBlock(base.offset(dx, 0, dz), Blocks.RED_MUSHROOM_BLOCK);
                helper.setBlock(base.offset(dx, 1, dz), Blocks.RED_MUSHROOM_BLOCK);
            }
        }

        ServerLevel level = helper.getLevel();
        BlockPos absoluteSoil = helper.absolutePos(soilPos);
        BlockPos absoluteBase = helper.absolutePos(base);
        helper.assertTrue(level.getBlockEntity(absoluteSoil) instanceof CompressedBlockEntity,
                "expected a chest in the compressed block at " + absoluteSoil);

        // 回填的是「被催熟的那个蘑菇状态」，蘑菇没有 STAGE 属性，原状态直接放回（ADR-0015）
        SettleResult result = TreeHarvest.harvest(level, absoluteBase, absoluteSoil, List.of(absoluteBase),
                Blocks.RED_MUSHROOM.defaultBlockState());
        helper.assertTrue(result == SettleResult.SUCCESS, "expected SUCCESS, got " + result);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos at = absoluteBase.offset(dx, dy, dz);
                    if (dx == 0 && dz == 0 && dy == 0) {
                        continue;
                    }
                    helper.assertTrue(level.getBlockState(at).isAir(),
                            "expected air at " + at + ", got " + level.getBlockState(at));
                }
            }
        }

        helper.assertTrue(level.getBlockState(absoluteBase).is(Blocks.RED_MUSHROOM),
                "expected a red mushroom refilled at " + absoluteBase + ", got " + level.getBlockState(absoluteBase));

        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absoluteSoil);
        long mushrooms = chest.storage().amountOf(BuiltInRegistries.ITEM.getKey(Items.RED_MUSHROOM));
        helper.assertTrue(mushrooms > 0L, "expected red mushrooms in the chest, got " + mushrooms);
        helper.assertTrue(mushrooms % 9L == 0L,
                "expected a multiple of 9^1 = 9 red mushrooms in the chest, got " + mushrooms);
        helper.succeed();
    }

    /** 手动兜底：挖掉压缩泥土上的蘑菇 → 1 个蘑菇 × 9^1 进货仓，事件被取消（产物不落世界）。 */
    @GameTest(template = TEMPLATE)
    public void collectsManuallyBrokenMushroomAndCancelsBreak(GameTestHelper helper) {
        BlockPos soilPos = new BlockPos(4, 1, 4);
        helper.setBlock(soilPos, ModBlocks.COMPRESSED_DIRT.get(0).get());
        BlockPos mushroomPos = soilPos.above();
        helper.setBlock(mushroomPos, Blocks.RED_MUSHROOM);

        ServerLevel level = helper.getLevel();
        BlockPos absoluteSoil = helper.absolutePos(soilPos);
        BlockPos absoluteMushroom = helper.absolutePos(mushroomPos);
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, absoluteMushroom,
                level.getBlockState(absoluteMushroom), null);

        new MushroomModule().onBlockBreak(event);

        helper.assertTrue(event.isCanceled(), "expected the break event to be canceled");
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absoluteSoil);
        long mushrooms = chest.storage().amountOf(BuiltInRegistries.ITEM.getKey(Items.RED_MUSHROOM));
        helper.assertTrue(mushrooms == 9L, "expected 1 mushroom x 9^1 = 9 in the chest, got " + mushrooms);
        helper.succeed();
    }
}
