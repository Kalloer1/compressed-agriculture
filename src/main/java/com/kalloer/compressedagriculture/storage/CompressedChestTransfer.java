package com.kalloer.compressedagriculture.storage;

import com.kalloer.compressedagriculture.block.CompressedBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 「把一个压缩方块的货仓搬到同一个坐标上的另一个压缩方块」——形态互转（本票）与活塞迁移（09b）共用的唯一入口
 * （ADR-0002、ADR-0019）。
 *
 * <p>顺序固定为「先读旧数据 → 再换方块 → 再写回新 BlockEntity」：{@link ServerLevel#setBlock} 会连同旧 BlockEntity
 * 一起删掉，所以数据必须在换方块之前取出来；换方块失败（区块没加载等）时直接返回 {@code false}，旧数据仍留在原方块上，
 * 不会丢。
 *
 * <p>编码/解码只走 {@link CompressedBlockEntity#saveData} / {@link CompressedBlockEntity#loadData}，与存档、物品形态
 * （03b）是同一套 NBT，不存在第二份序列化代码。{@code chestCount}、27 格内容、{@code schemaVersion} 一并搬过去。
 */
public final class CompressedChestTransfer {
    private CompressedChestTransfer() {}

    /**
     * 把 {@code pos} 上的方块换成 {@code newState}，并把原方块货仓的全部数据搬到新方块的 BlockEntity 上。
     *
     * <p>只在本模组方块之间使用：{@code newState} 必须是 {@link CompressedBlock}（自带货仓），否则抛
     * {@link IllegalArgumentException}——那是调用方的编程错误，静默丢数据更难查。
     *
     * @return 换方块成功时为 {@code true}；世界拒绝放置时为 {@code false}，此时什么都没改
     */
    public static boolean convert(ServerLevel level, BlockPos pos, BlockState newState) {
        if (!(newState.getBlock() instanceof CompressedBlock)) {
            throw new IllegalArgumentException("newState must be a compressed block, but was " + newState);
        }

        BlockEntity source = level.getBlockEntity(pos);
        CompoundTag data = source instanceof CompressedBlockEntity chest
                ? CompressedBlockEntity.saveData(chest, level.registryAccess())
                : null;

        if (!level.setBlock(pos, newState, Block.UPDATE_ALL)) {
            return false;
        }

        BlockEntity created = level.getBlockEntity(pos);
        if (!(created instanceof CompressedBlockEntity target)) {
            // 压缩方块必然实现 EntityBlock，走不到这里；真走到了也不抛异常，只是新方块是空货仓
            return false;
        }
        if (data != null) {
            CompressedBlockEntity.loadData(target, data, level.registryAccess());
        }
        target.setChanged();
        return true;
    }
}
