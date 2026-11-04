package com.kalloer.compressedagriculture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kalloer.compressedagriculture.recipe.CompressedExpansionRecipe;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 自动合成器不许用「一格多个箱子」的扩容配方（BUG3）。
 *
 * <p>为什么光有 {@code ExpansionConsumptionHandler} 不够：{@code CrafterBlock#dispenseFrom} 走的是
 * {@code getPotentialResults → getRecipeFor}（不排除 CustomRecipe）→ {@code assemble} → 每格 {@code shrink(1)}，
 * <b>整条路径不抛 {@code ItemCraftedEvent}</b>，所以按玩家合成补扣的那套在这里完全不会跑。结果就是「一次脉冲 +64 档、
 * 只吃 1 个箱子」——红石全自动的 64 倍复制。
 *
 * <p>处理方式：{@code dispenseFrom} 一开头就检查矩阵，只要「匹配扩容配方」且「有箱子格装了不止 1 个」，就放原版
 * 失败音效（{@code levelEvent 1050}，与「没有匹配配方」同一反馈）并取消本次合成。没有像玩家合成那样「改写成 1 个」，
 * 是因为自动合成器的箱子存在自己的 BlockEntity 里、没有「下一次 onTake 会来拿走剩下的」这一步，缩到 1 等于凭空销毁
 * 63 个箱子。
 *
 * <p>单格只有 1 个箱子时<b>照常合成</b>：消耗 1 个、记 1 档，与原版消耗量一致，不复制。玩家想让自动合成器跑扩容，
 * 每格摆 1 个箱子即可。
 */
@Mixin(CrafterBlock.class)
public abstract class CrafterBlockMixin {
    /**
     * 注入点在方法最开头：此时还没算配方、没扣料，取消掉等价于「这次脉冲什么都没发生」。
     *
     * <p>矩阵取 {@code CrafterBlockEntity#asCraftInput()}——与 {@code dispenseFrom} 里取的是同一个来源
     * （{@code CraftingContainer} 的 default 方法，3×3）。
     */
    @Inject(
            method = "dispenseFrom(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void compressedagriculture$refuseOversizedChestStack(BlockState state, ServerLevel level, BlockPos pos,
            CallbackInfo callback) {
        if (!(level.getBlockEntity(pos) instanceof CrafterBlockEntity crafter)) {
            return;
        }
        CraftingInput input = crafter.asCraftInput();
        if (CompressedExpansionRecipe.matchesExpansion(input)
                && CompressedExpansionRecipe.hasOversizedChestStack(input)) {
            level.levelEvent(1050, pos, 0);
            callback.cancel();
        }
    }
}
