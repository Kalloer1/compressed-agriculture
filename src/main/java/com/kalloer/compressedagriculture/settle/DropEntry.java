package com.kalloer.compressedagriculture.settle;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

/**
 * 掉落清单里的一条：物品 ID + 数量。
 *
 * <p>{@code itemId} 非空，{@code count > 0}。数量为 0 或负的掉落条目由调用方在转成 {@code DropEntry} 之前丢弃（ADR-0017）。
 * 同一物品的多条条目由 {@link Settle#settle} 内部先合并、再乘倍率。
 */
public record DropEntry(ResourceLocation itemId, long count) {
    public DropEntry {
        Objects.requireNonNull(itemId, "itemId");
        if (count <= 0L) {
            throw new IllegalArgumentException("count must be positive, but was " + count + " for " + itemId);
        }
    }
}
