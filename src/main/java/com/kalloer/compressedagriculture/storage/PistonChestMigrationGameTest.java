package com.kalloer.compressedagriculture.storage;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 活塞迁移的 GameTest（ticket 09b）。
 *
 * <p>布局统一是「红石块 → 活塞 → 压缩方块 → 空格」，全部落在 {@code empty} 模板的 5×5×5 范围内：
 * <ul>
 *   <li>{@link #pistonPushKeepsChest}：普通活塞推一格，货仓跟着走；</li>
 *   <li>{@link #stickyPistonPullKeepsChest}：粘性活塞先被红石块通电保持伸出，再断电拉回，货仓跟着回来。</li>
 * </ul>
 *
 * <p>活塞走完整个动作要 3 tick（{@code progress} 每 tick +0.5，{@code progressO >= 1} 那 tick 才放置方块），所以断言都放在
 * {@code runAfterDelay(10, ...)} 里。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class PistonChestMigrationGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation WHEAT = BuiltInRegistries.ITEM.getKey(Items.WHEAT);

    /** 红石块、活塞、压缩方块、空位的相对坐标。 */
    private static final BlockPos POWER = new BlockPos(0, 1, 2);
    private static final BlockPos PISTON = new BlockPos(1, 1, 2);
    private static final BlockPos CHEST = new BlockPos(2, 1, 2);
    private static final BlockPos DEST = new BlockPos(3, 1, 2);

    /** 普通活塞把装了货仓的压缩泥土推一格：目标位置是压缩泥土、货仓一字不差，源位置被活塞头占住。 */
    @GameTest(template = TEMPLATE)
    public void pistonPushKeepsChest(GameTestHelper helper) {
        helper.setBlock(PISTON, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        helper.setBlock(CHEST, ModBlocks.COMPRESSED_DIRT.get(2).get());

        ServerLevel level = helper.getLevel();
        CompoundTag expected = fillChest(helper, level, CHEST, 3);
        helper.setBlock(POWER, Blocks.REDSTONE_BLOCK);

        helper.runAfterDelay(10, () -> {
            helper.assertTrue(level.getBlockState(helper.absolutePos(DEST)).is(ModBlocks.COMPRESSED_DIRT.get(2).get()),
                    "expected compressed dirt of level 3 at " + DEST + ", got "
                            + level.getBlockState(helper.absolutePos(DEST)));
            helper.assertTrue(level.getBlockState(helper.absolutePos(CHEST)).is(Blocks.PISTON_HEAD),
                    "expected the piston head to take the vacated source position, got "
                            + level.getBlockState(helper.absolutePos(CHEST)));
            assertChestData(helper, level, DEST, expected, "after the push");
            helper.succeed();
        });
    }

    /** 粘性活塞拉回：压缩方块先被推出，再被拉回原位，货仓全程跟着走。 */
    @GameTest(template = TEMPLATE)
    public void stickyPistonPullKeepsChest(GameTestHelper helper) {
        // 先给电，活塞才是「伸出的」状态；断电前放的方块不会触发提前收缩
        helper.setBlock(POWER, Blocks.REDSTONE_BLOCK);
        helper.setBlock(PISTON, Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST)
                .setValue(PistonBaseBlock.EXTENDED, true));
        helper.setBlock(CHEST, Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(PistonHeadBlock.FACING, Direction.EAST));
        helper.setBlock(DEST, ModBlocks.COMPRESSED_DIRT.get(4).get());

        ServerLevel level = helper.getLevel();
        CompoundTag expected = fillChest(helper, level, DEST, 5);

        helper.runAfterDelay(2, () -> {
            // 断电 → 粘性活塞收缩，把 DEST 上的压缩方块拉回 CHEST
            helper.setBlock(POWER, Blocks.AIR);
            helper.runAfterDelay(10, () -> {
                helper.assertTrue(
                        level.getBlockState(helper.absolutePos(CHEST)).is(ModBlocks.COMPRESSED_DIRT.get(4).get()),
                        "expected the pulled block back at " + CHEST + ", got "
                                + level.getBlockState(helper.absolutePos(CHEST)));
                helper.assertTrue(level.getBlockState(helper.absolutePos(DEST)).isAir(),
                        "expected the pulled-from position to be air, got "
                                + level.getBlockState(helper.absolutePos(DEST)));
                assertChestData(helper, level, CHEST, expected, "after the pull");
                helper.succeed();
            });
        });
    }

    /** 往 {@code pos} 的货仓里塞点东西，返回搬运前的完整编码，供事后逐字节比对。 */
    private static CompoundTag fillChest(GameTestHelper helper, ServerLevel level, BlockPos pos, long chestCount) {
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(helper.absolutePos(pos));
        helper.assertTrue(chest != null, "expected a chest block entity at " + pos);
        chest.storage().setChestCount(chestCount);
        chest.storage().tryReserve(WHEAT, 100L * chestCount);
        chest.storage().commit();
        return CompressedBlockEntity.saveData(chest, level.registryAccess());
    }

    private static void assertChestData(GameTestHelper helper, ServerLevel level, BlockPos pos, CompoundTag expected,
            String when) {
        BlockState state = level.getBlockState(helper.absolutePos(pos));
        helper.assertTrue(state.getBlock() instanceof CompressedBlock,
                "expected a compressed block at " + pos + " " + when + ", got " + state);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(helper.absolutePos(pos));
        helper.assertTrue(chest != null, "expected a chest block entity at " + pos + " " + when);
        CompoundTag actual = CompressedBlockEntity.saveData(chest, level.registryAccess());
        helper.assertTrue(expected.equals(actual),
                "expected the chest data to survive " + when + ", expected=" + expected + " actual=" + actual);
    }
}
