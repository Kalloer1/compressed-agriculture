package com.kalloer.compressedagriculture.mixin;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kalloer.compressedagriculture.storage.PistonChestCarrier;
import com.kalloer.compressedagriculture.storage.PistonChestMigration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 给移动中的方块一个货仓口袋（ticket 09b、{@link PistonChestCarrier}）。
 *
 * <p>原版 {@code PistonMovingBlockEntity} 只存 {@code movedState}，方块被推着走的那几 tick 里货仓没有落脚点。这里加一个
 * {@code @Unique CompoundTag}，并让它跟着 {@code saveAdditional} / {@code loadAdditional} 一起进出 NBT——移动只有 2–3 tick，
 * 但区块卸载仍可能发生，不写盘就会丢。
 *
 * <p>落点还原有两个注入点，对应原版的两条放置路径（{@code javap} 复核：{@code tick} 的静态方法里内联了一份和
 * {@code finalTick} 一样的放置逻辑，两条都要接）：
 * <ul>
 *   <li>{@code tick(Level, BlockPos, BlockState, PistonMovingBlockEntity)} 的 {@code TAIL}：服务端正常推进走这条；</li>
 *   <li>{@code finalTick()} 的 {@code TAIL}：另一条放置入口，可能被外部直接调用。</li>
 * </ul>
 * 两处都调 {@link PistonChestMigration#restore}，成功后数据被清空，所以即使两条都跑到也只应用一次。
 */
@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityMixin implements PistonChestCarrier {
    /** NBT 键名带命名空间，避免和原版的 {@code blockState} / {@code facing} / {@code progress} 撞。 */
    @Unique
    private static final String COMPRESSEDAGRICULTURE$CHEST_KEY = "compressedagriculture:chest";

    @Unique
    @Nullable
    private CompoundTag compressedagriculture$chestData;

    @Override
    @Nullable
    public CompoundTag compressedagriculture$chestData() {
        return this.compressedagriculture$chestData;
    }

    @Override
    public void compressedagriculture$setChestData(@Nullable CompoundTag data) {
        this.compressedagriculture$chestData = data;
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void compressedagriculture$saveChest(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (this.compressedagriculture$chestData != null) {
            tag.put(COMPRESSEDAGRICULTURE$CHEST_KEY, this.compressedagriculture$chestData.copy());
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void compressedagriculture$loadChest(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        this.compressedagriculture$chestData = tag.contains(COMPRESSEDAGRICULTURE$CHEST_KEY)
                ? tag.getCompound(COMPRESSEDAGRICULTURE$CHEST_KEY)
                : null;
    }

    /** 服务端的正常推进路径：{@code tick} 末尾方块已经落定。 */
    @Inject(method = "tick", at = @At("TAIL"))
    private static void compressedagriculture$restoreChestAfterTick(Level level, BlockPos pos, BlockState state,
            PistonMovingBlockEntity moving, CallbackInfo ci) {
        PistonChestMigration.restore(level, pos, moving);
    }

    /** 另一条放置入口：{@code finalTick()} 末尾同样可能刚把方块放下。 */
    @Inject(method = "finalTick", at = @At("TAIL"))
    private void compressedagriculture$restoreChestAfterFinalTick(CallbackInfo ci) {
        PistonMovingBlockEntity self = (PistonMovingBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level != null) {
            PistonChestMigration.restore(level, self.getBlockPos(), self);
        }
    }
}
