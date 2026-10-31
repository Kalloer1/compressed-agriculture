package com.kalloer.compressedagriculture.storage;

import com.kalloer.compressedagriculture.registry.ModBlockEntities;
import com.kalloer.compressedagriculture.settle.ChestTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 27 个压缩方块共用的 BlockEntity：只装货仓，不装等级、不装形态——那两样由方块本身表达。
 *
 * <p>分工：{@link ChestStorage} 负责数据与三阶段契约，本类负责「存档 + 改动后通知世界」。
 * 实现 {@link ChestTarget} 是为了让 {@code settle} 直接把本对象当真货仓写入；{@link #commit()} 比
 * {@link ChestStorage#commit()} 多一步 {@link #setChanged()}，否则区块卸载时改动会丢。
 *
 * <p>1.21.1 的序列化签名是 {@code saveAdditional(CompoundTag, HolderLookup.Provider)} /
 * {@code loadAdditional(CompoundTag, HolderLookup.Provider)}。
 */
public class CompressedBlockEntity extends BlockEntity implements ChestTarget {
    private final ChestStorage storage = new ChestStorage();

    public CompressedBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COMPRESSED.get(), pos, state);
    }

    /** 货仓本体，给调试（{@code /data get block}）、ticket 08 的 GUI 与单元测试用。 */
    public ChestStorage storage() {
        return this.storage;
    }

    @Override
    public long capacityPerSlot() {
        return this.storage.capacityPerSlot();
    }

    @Override
    public boolean tryReserve(ResourceLocation itemId, long amount) {
        return this.storage.tryReserve(itemId, amount);
    }

    @Override
    public void commit() {
        this.storage.commit();
        setChanged();
    }

    @Override
    public void rollback() {
        this.storage.rollback();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        this.storage.save(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.storage.load(tag);
        // chestCount 已 clamp；加载阶段 level 还没挂上，setChanged() 此时是 no-op，挂上后才会真的通知世界
        setChanged();
    }
}
