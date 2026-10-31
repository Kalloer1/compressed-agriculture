package com.kalloer.compressedagriculture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.kalloer.compressedagriculture.block.CompressedFarmlandBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 瓶子草不继承 {@code CropBlock}，{@code mayPlaceOn} 自己写了一份 {@code state.is(Blocks.FARMLAND)}，
 * 所以单独注入一次（ADR-0018）。
 */
@Mixin(PitcherCropBlock.class)
public abstract class PitcherCropBlockMixin {
    @Inject(
            method = "mayPlaceOn(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("RETURN"),
            cancellable = true)
    private void compressedagriculture$allowCompressedFarmland(BlockState state, BlockGetter level, BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && state.getBlock() instanceof CompressedFarmlandBlock) {
            cir.setReturnValue(true);
        }
    }
}
