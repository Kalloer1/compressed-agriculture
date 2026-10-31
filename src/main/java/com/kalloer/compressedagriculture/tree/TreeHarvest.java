package com.kalloer.compressedagriculture.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import javax.annotation.Nullable;

import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.settle.DropEntry;
import com.kalloer.compressedagriculture.settle.Settle;
import com.kalloer.compressedagriculture.settle.SettleResult;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 「枚举 → 取掉落 → 结算 → 拆除 → 回填 → 反馈」这条链的可调用单元（ADR-0011 / ADR-0014）。
 *
 * <p>不监听任何事件、不认具体树苗——{@code 06} 的蘑菇树会用同一个单元，只是传入不同的 {@code trunkPos} 与回填方块。
 *
 * <p>拆除用 {@link ServerLevel#setBlock} 而不是 {@code destroyBlock} / {@code dropResources}：这不是玩家破坏，
 * 不能触发 {@code BreakEvent} / {@code BlockDropsEvent}，否则经验、统计、成就会误加（ADR-0011）。
 *
 * <p>先拆后填：回填的树苗不会被自己的拆除逻辑命中，且原位已被占用时不覆盖。
 */
public final class TreeHarvest {
    private TreeHarvest() {}

    /**
     * 砍掉 {@code trunkPos} 所在的整棵树，产物乘 {@code 9^L} 写进 {@code chestPos} 的货仓，然后在 {@code refillCells} 回填树苗。
     *
     * @param trunkPos   枚举起点：树干最低一格（{@link TreeEnumeration#enumerate} 会自己向上找树干）
     * @param chestPos   容器所在格，{@code L} 由它的方块状态读出（ADR-0006）
     * @param refillCells 拆完要回填树苗的位置，顺序即写入顺序；2×2 巨树是西北 → 东北 → 西南 → 东南四格
     * @param refillState 回填的树苗状态，{@code STAGE} 会被强制置 0（ADR-0014）
     * @return {@link SettleResult#SUCCESS} 时世界已被改过；其余情况世界一个方块都没动
     */
    public static SettleResult harvest(ServerLevel level, BlockPos trunkPos, BlockPos chestPos,
            List<BlockPos> refillCells, BlockState refillState) {
        TreeEnumeration.Result tree = TreeEnumeration.enumerate(level, trunkPos);
        if (tree.isEmpty()) {
            return SettleResult.INVALID_INPUT;
        }

        List<DropEntry> drops = collectDrops(level, tree);
        if (drops.isEmpty()) {
            return SettleResult.INVALID_INPUT;
        }

        OptionalInt chestLevel = CompressedBlock.levelOf(level.getBlockState(chestPos));
        if (chestLevel.isEmpty() || !(level.getBlockEntity(chestPos) instanceof CompressedBlockEntity chest)) {
            return SettleResult.INVALID_INPUT;
        }

        SettleResult result = Settle.settle(drops, chestLevel.getAsInt(), chest);
        if (result != SettleResult.SUCCESS) {
            // INSUFFICIENT_SPACE：树完整保留，等外部事件驱动重试（ADR-0005）
            return result;
        }

        BlockState sampleLog = sampleLogState(level, tree);
        removeAll(level, tree);
        refill(level, refillCells, refillState);
        playFeedback(level, chestPos, sampleLog);
        return result;
    }

    /** 按 {@code logs → leaves → attachments} 的固定顺序逐个方块取真实掉落（ADR-0011）。 */
    private static List<DropEntry> collectDrops(ServerLevel level, TreeEnumeration.Result tree) {
        List<DropEntry> drops = new ArrayList<>();
        collectDrops(level, tree.logs(), drops);
        collectDrops(level, tree.leaves(), drops);
        collectDrops(level, tree.attachments(), drops);
        return drops;
    }

    private static void collectDrops(ServerLevel level, Set<BlockPos> positions, List<DropEntry> drops) {
        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            for (ItemStack stack : Block.getDrops(state, level, pos, blockEntity, null, ItemStack.EMPTY)) {
                if (stack.isEmpty() || stack.getCount() <= 0) {
                    continue;
                }
                drops.add(new DropEntry(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount()));
            }
        }
    }

    /** 枚举出的每个位置都设为空气。只通知客户端，不做方块更新，也不产生掉落物（ADR-0011）。 */
    private static void removeAll(ServerLevel level, TreeEnumeration.Result tree) {
        BlockState air = Blocks.AIR.defaultBlockState();
        removeAll(level, tree.logs(), air);
        removeAll(level, tree.leaves(), air);
        removeAll(level, tree.attachments(), air);
    }

    private static void removeAll(ServerLevel level, Set<BlockPos> positions, BlockState air) {
        for (BlockPos pos : positions) {
            level.setBlock(pos, air, Block.UPDATE_CLIENTS);
        }
    }

    /** 原位是空气才放回树苗；已被别的方块占据就跳过，不覆盖（ADR-0014）。 */
    private static void refill(ServerLevel level, List<BlockPos> cells, BlockState refillState) {
        BlockState stageZero = stageZero(refillState);
        for (BlockPos cell : cells) {
            if (!level.getBlockState(cell).isAir()) {
                continue;
            }
            level.setBlock(cell, stageZero, Block.UPDATE_ALL);
        }
    }

    /** {@code STAGE} 必须是 0，否则下一轮树苗立刻满足 {@code growTree} 条件，变成拆了就马上再长的死循环（ADR-0014）。 */
    private static BlockState stageZero(BlockState state) {
        if (state.hasProperty(BlockStateProperties.STAGE)) {
            return state.setValue(BlockStateProperties.STAGE, 0);
        }
        return state;
    }

    /** 反馈用的样本方块状态：拆之前取一根原木，拆完就没得取了。 */
    @Nullable
    private static BlockState sampleLogState(ServerLevel level, TreeEnumeration.Result tree) {
        for (BlockPos pos : tree.logs()) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                return state;
            }
        }
        return null;
    }

    /** 木质破坏音效 + 少量木屑粒子，位置在容器上方（§6.4）。 */
    private static void playFeedback(ServerLevel level, BlockPos chestPos, @Nullable BlockState sampleLog) {
        BlockPos at = chestPos.above();
        level.playSound(null, at, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.0F, 0.8F);
        if (sampleLog == null) {
            return;
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, sampleLog),
                at.getX() + 0.5D, at.getY() + 0.5D, at.getZ() + 0.5D, 8, 0.3D, 0.3D, 0.3D, 0.05D);
    }
}
