package com.kalloer.compressedagriculture.storage;

import com.kalloer.compressedagriculture.registry.ModBlockEntities;
import com.kalloer.compressedagriculture.settle.ChestTarget;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
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

    /**
     * 客户端要显示货仓内容，所以区块包必须带上货仓数据（ticket 08 的同步方式①）。
     *
     * <p>用 {@link #saveWithoutMetadata} 当同步快照：与存档、物品形态（03b）、形态互转（09）是同一份编码，
     * 不另写一套「同步用的 NBT」。不带 {@code id} / 坐标，因为客户端已经知道自己在加载哪个方块。
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    /** 方块已加载后再改内容时（界面取出）走这条增量包，靠 {@link #changedAndSync} 触发。 */
    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * 内容被改动后调：标脏存档，并把整份货仓数据推给客户端。
     *
     * <p>{@link #setChanged()} 只标脏区块，不发包；界面上取走物品后客户端要靠这一下刷新计数，否则显示会停在旧值。
     */
    public void changedAndSync() {
        setChanged();
        if (this.level != null && !this.level.isClientSide()) {
            this.level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.storage.load(tag);
        // chestCount 已 clamp；加载阶段 level 还没挂上，setChanged() 此时是 no-op，挂上后才会真的通知世界
        setChanged();
    }

    /**
     * 把一个压缩方块的全部状态编码成 {@link CompoundTag}，供「物品形态」（ticket 03b）与
     * 「活塞迁移 / 形态转换」（ticket 09）搬运。
     *
     * <p>直接转调 {@link BlockEntity#saveWithoutMetadata}，也就是走 {@link #saveAdditional}——搬运与存档共用同一套编码，
     * 不存在第二份序列化代码。不带 {@code id} / {@code x} / {@code y} / {@code z}：那四个字段描述的是「存档里的哪个方块」，
     * 搬进物品或另一个坐标就错了。
     */
    public static CompoundTag saveData(CompressedBlockEntity chest, HolderLookup.Provider registries) {
        return chest.saveWithoutMetadata(registries);
    }

    /**
     * {@link #saveData} 的逆操作：把 {@code tag} 整体覆盖到 {@code chest} 上（走 {@link #loadAdditional}）。
     *
     * <p>与存档读回是同一条路径，所以 {@code chestCount} 的 clamp 与坏格丢弃规则在这里同样生效。
     */
    public static void loadData(CompressedBlockEntity chest, CompoundTag tag, HolderLookup.Provider registries) {
        chest.loadWithComponents(tag, registries);
    }
}
