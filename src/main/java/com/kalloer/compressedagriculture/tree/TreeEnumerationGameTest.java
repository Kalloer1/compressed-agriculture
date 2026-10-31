package com.kalloer.compressedagriculture.tree;

import java.util.Collections;

import com.kalloer.compressedagriculture.CompressedAgriculture;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@link TreeEnumeration} 的 GameTest（ticket 05 的硬验收）。
 *
 * <p>模板 {@code compressedagriculture:empty} 是一个 16×16×16 的空结构，场景全部由测试自己用 {@code helper.setBlock} 搭。
 * 结构内可用的相对坐标是 x 0..15、y 1..16、z 0..15（相对 y=0 那一层放的是结构方块与命令方块）。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class TreeEnumerationGameTest {
    private static final String TEMPLATE = "empty";

    /** 用例 1：3 根纵向原木 + 一圈带 distance 的树叶 → 3 根原木，树叶非空。 */
    @GameTest(template = TEMPLATE)
    public void enumeratesLogsAndLeaves(GameTestHelper helper) {
        BlockPos base = new BlockPos(2, 1, 2);
        buildOakShape(helper, base);

        ServerLevel level = helper.getLevel();
        BlockPos absoluteBase = helper.absolutePos(base);
        TreeEnumeration.Result result = TreeEnumeration.enumerate(level, absoluteBase);

        helper.assertTrue(result.logs().size() == 3, "expected 3 logs, got " + result.logs().size()
                + " column=" + column(level, absoluteBase) + " logs=" + result.logs());
        helper.assertTrue(!result.leaves().isEmpty(), "expected leaves, got none");
        helper.assertTrue(result.attachments().isEmpty(), "expected no attachments");
        helper.succeed();
    }

    /** 用例 2：让原版真的长一棵橡树，再枚举 → 原木非空、树叶非空。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public void enumeratesVanillaGrownOak(GameTestHelper helper) {
        BlockPos soilPos = new BlockPos(8, 1, 8);
        helper.setBlock(soilPos, Blocks.DIRT);
        BlockPos saplingPos = soilPos.above();
        helper.setBlock(saplingPos, Blocks.OAK_SAPLING.defaultBlockState().setValue(SaplingBlock.STAGE, 1));

        ServerLevel level = helper.getLevel();
        BlockPos absoluteSapling = helper.absolutePos(saplingPos);
        BlockState saplingState = level.getBlockState(absoluteSapling);
        // NeoForge 21.1 的 {@code BonemealableBlock#performBonemeal} 返回 void，靠读方块状态判断是否长成。
        ((BonemealableBlock) saplingState.getBlock())
                .performBonemeal(level, level.getRandom(), absoluteSapling, saplingState);
        helper.assertTrue(level.getBlockState(absoluteSapling).is(BlockTags.LOGS),
                "expected a log at the sapling position after growth, got "
                        + level.getBlockState(absoluteSapling));

        TreeEnumeration.Result result = TreeEnumeration.enumerate(level, absoluteSapling);
        helper.assertTrue(!result.logs().isEmpty(), "expected logs, got none");
        helper.assertTrue(!result.leaves().isEmpty(), "expected leaves, got none");
        helper.succeed();
    }

    /** 用例 3：树根可以砍，中段与顶端拒绝。 */
    @GameTest(template = TEMPLATE)
    public void validateCutRejectsMiddleOfTrunk(GameTestHelper helper) {
        BlockPos base = new BlockPos(2, 1, 2);
        buildOakShape(helper, base);

        ServerLevel level = helper.getLevel();
        helper.assertTrue(TreeEnumeration.validateCut(level, helper.absolutePos(base)),
                "cutting the lowest log should be allowed");
        helper.assertFalse(TreeEnumeration.validateCut(level, helper.absolutePos(base.above())),
                "cutting the middle log should be refused");
        helper.assertFalse(TreeEnumeration.validateCut(level, helper.absolutePos(base.above(2))),
                "cutting the top log should be refused");
        helper.succeed();
    }

    /** 用例 4：蘑菇树 → MUSHROOM_STEM 进 logs，蘑菇块进 leaves。 */
    @GameTest(template = TEMPLATE)
    public void enumeratesMushroomTree(GameTestHelper helper) {
        BlockPos base = new BlockPos(3, 1, 3);
        helper.setBlock(base, Blocks.MUSHROOM_STEM);
        helper.setBlock(base.above(), Blocks.MUSHROOM_STEM);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int horizontal = Math.max(Math.abs(dx), Math.abs(dz));
                if (horizontal == 0 || horizontal > 2) {
                    continue;
                }
                for (int dy = 0; dy <= 1; dy++) {
                    helper.setBlock(base.offset(dx, dy, dz), Blocks.RED_MUSHROOM_BLOCK);
                }
            }
        }

        ServerLevel level = helper.getLevel();
        TreeEnumeration.Result result = TreeEnumeration.enumerate(level, helper.absolutePos(base));

        helper.assertTrue(result.logs().size() == 2, "expected 2 mushroom stems, got " + result.logs().size()
                + " column=" + column(level, helper.absolutePos(base)) + " logs=" + result.logs());
        for (BlockPos pos : result.logs()) {
            helper.assertTrue(level.getBlockState(pos).is(Blocks.MUSHROOM_STEM),
                    "expected only mushroom stems in logs, got " + level.getBlockState(pos) + " at " + pos);
        }
        helper.assertTrue(!result.leaves().isEmpty(), "expected mushroom blocks in leaves, got none");
        for (BlockPos pos : result.leaves()) {
            helper.assertTrue(level.getBlockState(pos).is(Blocks.RED_MUSHROOM_BLOCK),
                    "expected only mushroom blocks in leaves, got " + level.getBlockState(pos) + " at " + pos);
        }
        helper.succeed();
    }

    /** 用例 5：同一位置不出现两次，且两次枚举结果一致（顺序固定、可复现）。 */
    @GameTest(template = TEMPLATE)
    public void enumerationIsDeduplicatedAndReproducible(GameTestHelper helper) {
        BlockPos base = new BlockPos(2, 1, 2);
        buildOakShape(helper, base);

        ServerLevel level = helper.getLevel();
        BlockPos absoluteBase = helper.absolutePos(base);
        TreeEnumeration.Result first = TreeEnumeration.enumerate(level, absoluteBase);
        TreeEnumeration.Result second = TreeEnumeration.enumerate(level, absoluteBase);

        helper.assertTrue(first.logs().equals(second.logs()), "logs should be reproducible across calls");
        helper.assertTrue(first.leaves().equals(second.leaves()), "leaves should be reproducible across calls");
        helper.assertTrue(Collections.disjoint(first.logs(), first.leaves()), "logs and leaves must not overlap");
        helper.assertTrue(Collections.disjoint(first.logs(), first.attachments()),
                "logs and attachments must not overlap");
        helper.assertTrue(Collections.disjoint(first.leaves(), first.attachments()),
                "leaves and attachments must not overlap");
        helper.assertTrue(first.logs().size() == 3, "expected 3 distinct logs, got " + first.logs().size());
        helper.succeed();
    }

    /** 诊断用：把某个位置上下 6 格的方块状态拼成一行。 */
    private static String column(ServerLevel level, BlockPos pos) {
        StringBuilder sb = new StringBuilder();
        for (int dy = -1; dy <= 4; dy++) {
            BlockPos at = pos.above(dy);
            sb.append(dy).append('=').append(level.getBlockState(at)).append(' ');
        }
        return sb.toString();
    }

    /** 搭一棵「橡树形状」：3 根纵向原木 + 每层 5×5 去掉树干的树叶，distance 用离树干的切比雪夫距离。 */
    private static void buildOakShape(GameTestHelper helper, BlockPos base) {
        for (int dy = 0; dy <= 2; dy++) {
            helper.setBlock(base.above(dy), Blocks.OAK_LOG);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int distance = Math.max(Math.abs(dx), Math.abs(dz));
                if (distance == 0) {
                    continue;
                }
                for (int dy = 0; dy <= 2; dy++) {
                    helper.setBlock(base.offset(dx, dy, dz), leaves(distance));
                }
            }
        }
    }

    private static BlockState leaves(int distance) {
        return Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.DISTANCE, distance)
                .setValue(LeavesBlock.PERSISTENT, true);
    }
}
