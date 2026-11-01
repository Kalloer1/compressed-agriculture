package com.kalloer.compressedagriculture.client;

import com.kalloer.compressedagriculture.menu.CompressedChestMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 27 格货仓界面（ticket 08）。
 *
 * <p>没有自己的贴图：面板与格子用 {@link GuiGraphics#fill} 画，背景沿用
 * {@link AbstractContainerScreen} 的默认 {@code renderBackground}（模糊世界 + 半透明底）。目标是「不崩、能看出格子」。
 *
 * <p>三处数据来源都是同步过来的值：图标来自槽位同步的展示栈，精确的 {@code long} 计数来自同步过的
 * BlockEntity 货仓（{@link CompressedChestMenu#countInSlot}），等级与容量档位来自数据槽。
 */
public class CompressedChestScreen extends AbstractContainerScreen<CompressedChestMenu> {
    private static final int PANEL_COLOR = 0xC0101010;
    private static final int SLOT_BORDER_COLOR = 0xFF373737;
    private static final int SLOT_COLOR = 0xFF8B8B8B;
    private static final int LABEL_COLOR = 0xFFE0E0E0;
    private static final int COUNT_COLOR = 0xFFFFFFFF;
    private static final int HEADER_COLOR = 0xFFE0E0E0;

    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 176;
    private static final int HEADER_Y = 17;
    private static final int HEADER_RIGHT_X = 90;

    public CompressedChestScreen(CompressedChestMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = PANEL_WIDTH;
        this.imageHeight = PANEL_HEIGHT;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelY = 84;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(this.leftPos, this.topPos,
                this.leftPos + this.imageWidth, this.topPos + this.imageHeight, PANEL_COLOR);
        for (Slot slot : this.menu.slots) {
            int x = this.leftPos + slot.x;
            int y = this.topPos + slot.y;
            guiGraphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_BORDER_COLOR);
            guiGraphics.fill(x, y, x + 16, y + 16, SLOT_COLOR);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, LABEL_COLOR, false);

        // ADR-0003：等级与容量档位是两个正交轴，界面上必须同时看得到，玩家才知道该扩哪个
        String level = "Level: " + this.menu.blockLevel();
        String capacity = "Capacity: " + shortCount(this.menu.chestCount());
        guiGraphics.drawString(this.font, level, this.titleLabelX, HEADER_Y, HEADER_COLOR, false);
        guiGraphics.drawString(this.font, capacity, HEADER_RIGHT_X, HEADER_Y, HEADER_COLOR, false);

        for (int slot = 0; slot < CompressedChestMenu.STORAGE_SLOTS; slot++) {
            long count = this.menu.countInSlot(slot);
            if (count <= 1L) {
                continue;
            }
            Slot menuSlot = this.menu.slots.get(slot);
            String text = shortCount(count);
            int x = this.leftPos + menuSlot.x + 17 - this.font.width(text);
            int y = this.topPos + menuSlot.y + 9;
            guiGraphics.drawString(this.font, text, x, y, COUNT_COLOR, true);
        }
    }

    /**
     * 计数短格式：{@code long} 计数最大到 {@code 64_000_000_064}，直接画会溢出格子。
     *
     * <p>一万以下原样显示，之后按 k / M / G 保留一位小数（{@code 12345 → 12.3k}）。用整数运算而不是
     * {@code String.format}，免得跟随系统区域设置变成逗号小数点。
     */
    static String shortCount(long count) {
        if (count < 10_000L) {
            return Long.toString(count);
        }
        if (count < 1_000_000L) {
            return tenths(count, 1_000L) + "k";
        }
        if (count < 1_000_000_000L) {
            return tenths(count, 1_000_000L) + "M";
        }
        return tenths(count, 1_000_000_000L) + "G";
    }

    private static String tenths(long count, long unit) {
        long value = count / (unit / 10L);
        return (value / 10L) + "." + (value % 10L);
    }
}
