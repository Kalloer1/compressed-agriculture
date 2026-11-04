package com.kalloer.compressedagriculture.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.kalloer.compressedagriculture.menu.CompressedChestMenu;
import com.kalloer.compressedagriculture.settle.ChestCapacity;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 27 格货仓界面（ticket 08）：外观就是原版 3 行箱子。
 *
 * <p>贴图直接用原版的 {@code textures/gui/container/generic_54.png}，两段 blit 拼成 3 行箱子的大小
 * （见 {@link #renderBg}），不额外带 PNG 资源。
 *
 * <p>三处数据来源都是同步过来的值：图标来自槽位同步的展示栈，精确的 {@code long} 计数来自同步过的
 * BlockEntity 货仓（{@link CompressedChestMenu#countInSlot}），等级与每格容量上限来自数据槽。
 */
public class CompressedChestScreen extends AbstractContainerScreen<CompressedChestMenu> {
    /** 原版箱子贴图：上半截是标题栏 + 货仓的 3 行格子，下半截（v=126）是玩家背包。 */
    private static final ResourceLocation CONTAINER_BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    /** 货仓的行数，与 {@link CompressedChestMenu#STORAGE_SLOTS} 对应（3 行 × 9 列 = 27 格）。 */
    private static final int STORAGE_ROWS = 3;
    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 168;
    /** 标题行右侧小字与标题之间至少留的间距。 */
    private static final int TITLE_GAP = 4;
    /** 面板右内边距，标题行小字右对齐到这个位置。 */
    private static final int RIGHT_PADDING = 8;

    public CompressedChestScreen(CompressedChestMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = IMAGE_WIDTH;
        this.imageHeight = IMAGE_HEIGHT;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelY = 74;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        // 原版箱子贴图分两段：v=0 起是「标题栏 + 3 行格子」（17 是标题栏高度，3*18 是格子区），
        // v=126 起是玩家背包。两段各自的高度是 71 和 96，跟 ContainerScreen#renderBg 的算法一致。
        int storageHeight = STORAGE_ROWS * 18 + 17;
        guiGraphics.blit(CONTAINER_BACKGROUND, this.leftPos, this.topPos, 0, 0, this.imageWidth, storageHeight);
        guiGraphics.blit(CONTAINER_BACKGROUND, this.leftPos, this.topPos + storageHeight, 0, 126,
                this.imageWidth, 96);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 4210752, false);
        // 原版箱子会自己画「Inventory」，但本类没调 super.renderLabels（标题要换成我们自己的），
        // 所以这句得自己补：贴图里没有烘焙好的文字，不画就是一块空白，跟原版对不上
        guiGraphics.drawString(this.font, this.playerInventoryTitle,
                this.inventoryLabelX, this.inventoryLabelY, 4210752, false);

        // ADR-0003：等级与容量档位是两个正交轴，界面上必须同时看得到，玩家才知道该扩哪个。
        // 坐标是面板相对坐标（AbstractContainerScreen 调 renderLabels 前 translate 过 leftPos/topPos）。
        Component capacity = capacityLabel();
        guiGraphics.drawString(this.font, capacity,
                this.imageWidth - RIGHT_PADDING - this.font.width(capacity), this.titleLabelY, 4210752, false);
    }

    /**
     * 数量显示：只留一个，1.0 倍，画在原版右下角的位置。
     *
     * <p><b>{@code renderSlotContents} 是 NeoForge 21.1.235 的补丁钩子，不是原版方法</b>——NeoForge 把
     * {@code AbstractContainerScreen#renderSlot} 尾部「画图标 + 画数量」那段抽成了这个 protected 方法
     * （vanilla 的 1.21.1 里没有它）。升级 NeoForge 时必须重新确认这个钩子还在、签名没变。
     *
     * <p>为什么不直接覆盖 {@code renderSlot}：那个方法要读 {@code clickedSlot}、{@code draggingItem}、
     * {@code isSplittingStack}、{@code quickCraftingType} 四个私有字段，子类编译不过；硬抄只能删掉
     * 拖拽分裂预览和 quick-craft 的黄色溢出数字。走这个钩子两者都原样保留。
     */
    @Override
    protected void renderSlotContents(GuiGraphics guiGraphics, ItemStack stack, Slot slot, @Nullable String countString) {
        String text = countString;
        if (text == null) {
            int index = this.menu.slots.indexOf(slot);
            if (index >= 0 && index < CompressedChestMenu.STORAGE_SLOTS) {
                long count = this.menu.countInSlot(index);
                if (count > 1L) {
                    text = shortCount(count);
                }
            }
        }
        super.renderSlotContents(guiGraphics, stack, slot, text);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // AbstractContainerScreen 自己不会调 renderTooltip，原版 ContainerScreen 就是在 render 里补的这一句
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    /** 悬停货仓格时，在原版那几行之后追加一行灰色的「真实计数 / 本格上限」。 */
    @Override
    protected void renderTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Slot hovered = this.hoveredSlot;
        int index = hovered == null ? -1 : this.menu.slots.indexOf(hovered);
        ItemStack stack = hovered == null ? ItemStack.EMPTY : hovered.getItem();
        long count = index < 0 || index >= CompressedChestMenu.STORAGE_SLOTS ? 0L : this.menu.countInSlot(index);
        if (stack.isEmpty() || count <= 0L || !this.menu.getCarried().isEmpty()) {
            super.renderTooltip(guiGraphics, mouseX, mouseY);
            return;
        }

        List<Component> lines = new ArrayList<>(this.getTooltipFromContainerItem(stack));
        lines.add(Component
                .translatable("gui.compressedagriculture.exact_count", thousands(count), thousands(perSlotLimit()))
                .withStyle(ChatFormatting.GRAY));
        guiGraphics.renderTooltip(this.font, lines, stack.getTooltipImage(), mouseX, mouseY);
    }

    /**
     * 标题行右侧的小字，形如 {@code L3 · 64/格}：等级 + 每格真实容量。
     *
     * <p>上限写的是「还能塞多少」（{@code 64 × (1 + chestCount)}），不是扩容档位本身。档位拉满时是
     * {@code 64000000064}，跟标题挤在同一行装不下，所以装不下就退化成缩写。
     */
    private Component capacityLabel() {
        long perSlotLimit = perSlotLimit();
        Component exact = levelAndLimit(Long.toString(perSlotLimit));
        // 可用宽度 = 面板宽 - 右侧留白 - 标题自身宽度 - 间距
        int available = this.imageWidth - RIGHT_PADDING - (this.titleLabelX + this.font.width(this.title) + TITLE_GAP);
        return this.font.width(exact) <= available ? exact : levelAndLimit(shortCount(perSlotLimit));
    }

    private Component levelAndLimit(String perSlotLimit) {
        return Component.translatable("gui.compressedagriculture.level", this.menu.blockLevel())
                .append(" · ")
                .append(Component.translatable("gui.compressedagriculture.per_slot", perSlotLimit));
    }

    /**
     * 每格上限。数据槽里的 {@code chestCount} 已由服务端 clamp 过，这里再 clamp 一次是因为本方法每帧都跑，
     * 一个越界值会让 {@link ChestCapacity#stackLimit} 抛异常、整个界面帧崩掉。
     */
    private long perSlotLimit() {
        long chestCount = Math.min(Math.max(this.menu.chestCount(), 0L), ChestCapacity.MAX_CHEST_COUNT);
        return ChestCapacity.stackLimit(chestCount);
    }

    /**
     * 千分位。必须 {@link Locale#ROOT}，否则跟随系统区域设置换成别的分隔符（{@link #shortCount} 的注释
     * 里已经踩过这个坑）。
     */
    private static String thousands(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /**
     * 计数短格式：{@code long} 计数最大到 {@code 64_000_000_064}，直接画会溢出 16px 的格子。
     *
     * <p>一千以下原样显示，之后按 k / M / G 缩写，且**最多 4 个字符**（{@code 6400 → 6.4k}、
     * {@code 64000 → 64k}、{@code 64_000_000 → 64M}）。只有整数部分是个位数时才带一位小数，所以不会出现
     * {@code 999.9k} 这种 5 字符输出。
     *
     * <p>4 个字符约 20px，画在原版右下角会向左溢出 2-4px，这是明确接受的取舍：宁可压一点格线，也不缩小
     * 字体（缩过一半的 6px 字看不清）。
     *
     * <p>小数是截断不是四舍五入（{@code 12399 → 12k}），属可接受的取舍：这是个量级提示，精确值看悬停
     * 提示里的千分位数字。用整数运算而不是 {@code String.format}，免得跟随系统区域设置变成逗号小数点。
     */
    static String shortCount(long count) {
        if (count < 1_000L) {
            return Long.toString(count);
        }
        if (count < 1_000_000L) {
            return abbreviated(count, 1_000L) + "k";
        }
        if (count < 1_000_000_000L) {
            return abbreviated(count, 1_000_000L) + "M";
        }
        return abbreviated(count, 1_000_000_000L) + "G";
    }

    private static String abbreviated(long count, long unit) {
        long whole = count / unit;
        long tenths = (count % unit) / (unit / 10L);
        return whole < 10L && tenths != 0L ? whole + "." + tenths : Long.toString(whole);
    }
}
