package com.kalloer.compressedagriculture.recipe;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 扩容配方的补扣（BUG3）：箱子按整格计入 {@code chestCount}，而原版一次合成每格只扣 1 个，差额在这里补。
 *
 * <p>{@code ResultSlot#onTake} 的时序是「先抛事件、再拍快照、最后扣料」，本类插在第一步：
 * <ol>
 *   <li>{@code checkTakeAchievements} → NeoForge 抛 {@link PlayerEvent.ItemCraftedEvent}，把<b>活的</b>合成矩阵交出来
 *       （此时扣料循环还没开始，改矩阵有效）；</li>
 *   <li>{@code getRemainingItemsFor} 拍下矩阵快照（看到的是本类改完的结果）；</li>
 *   <li>逐格 {@code removeItem(slot, 1)}，再把 remaining 合并回同一格。</li>
 * </ol>
 *
 * <p>所以「整格箱子」的处理方式是：把每个箱子格<b>改写成 1 个</b>——多出来的那部分当场销毁，剩下的 1 个由第 3 步的
 * 扣料循环拿走，合计恰好是「整格」。压缩方块格不动：本配方只扣 1 个方块，同槽里其余方块按原版语义留在格里。
 *
 * <p>客户端也会收到这个事件（客户端菜单有自己的点击预测），必须直接返回，否则客户端矩阵会被改花。
 *
 * <p><b>不变量：改写后矩阵必须仍然匹配 {@code matchesExpansion}。</b> 原因是原版在「找不到配方」时的兜底
 * <b>不是</b>全 {@code EMPTY}，而是把输入原样交回（{@code RecipeManager#getRemainingItemsFor} 的 {@code orElse} 分支，
 * 交回的还是同一批 {@code ItemStack} 引用），而 {@code ResultSlot#onTake} 随后会 {@code grow} 回去——
 * 于是每格翻倍。所以这里只能「改 count、不能清格」：一旦把某格写成 {@code EMPTY}，包围盒缩小、配方失配，
 * 立刻变成复制 bug。
 */
public final class ExpansionConsumptionHandler {
    private ExpansionConsumptionHandler() {
    }

    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        // 只认玩家的合成矩阵：3×3 工作台与 2×2 背包格都是 CraftingContainer，别的容器不碰
        Container craftMatrix = event.getInventory();
        if (!(craftMatrix instanceof CraftingContainer craftSlots)) {
            return;
        }
        // 兜底：矩阵不匹配扩容配方就什么都不做，否则会改掉所有其他配方的扣料结果
        CraftingInput input = CraftingInput.of(craftSlots.getWidth(), craftSlots.getHeight(), craftSlots.getItems());
        if (!CompressedExpansionRecipe.matchesExpansion(input)) {
            return;
        }
        for (int slot = 0; slot < craftSlots.getContainerSize(); slot++) {
            ItemStack stack = craftSlots.getItem(slot);
            // 已经是 1 个就不碰：少一次 setItem 就少一次 slotsChanged → 结果槽重算
            if (CompressedExpansionRecipe.isChest(stack) && stack.getCount() > 1) {
                craftSlots.setItem(slot, stack.copyWithCount(1));
            }
        }
    }
}
