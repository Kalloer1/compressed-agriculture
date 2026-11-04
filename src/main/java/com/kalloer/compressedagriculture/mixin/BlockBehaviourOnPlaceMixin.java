package com.kalloer.compressedagriculture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kalloer.compressedagriculture.topcut.TopCutModule;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 多格植物截顶的记录点（ADR-0021）。
 *
 * <p>注入 {@code BlockBehaviour#onPlace(BlockState, Level, BlockPos, BlockState, boolean)}——{@code LevelChunk.setBlockState}
 * 对每一次方块变更都会经由 {@code BlockStateBase.onPlace} 转发到这里，所以一处代码覆盖原版 + 模组的多格植物。
 * 三种默认成员（甘蔗 / 仙人掌 / 竹子）都不 override {@code onPlace}，注入点不会被绕开。
 *
 * <p><b>注入体只判定 + 记录，绝不 {@code setBlock}</b>：{@code onPlace} 处在 {@code setBlockState} 内部、邻居更新还没走完，
 * 在这里改世界是重入。真正的拆除在 {@link TopCutModule#onServerTick} 里做。
 *
 * <p>判定逻辑放在 {@link TopCutModule#onBlockPlaced} 而不是注入体里：注入体保持一行，判定顺序集中在一处可读。
 */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourOnPlaceMixin {
    @Inject(
            method = "onPlace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
            at = @At("HEAD"))
    private void compressedagriculture$recordTopCutCandidate(BlockState state, Level level, BlockPos pos,
            BlockState oldState, boolean movedByPiston, CallbackInfo ci) {
        TopCutModule.onBlockPlaced(level, pos, state);
    }
}
