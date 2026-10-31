package com.kalloer.compressedagriculture.crop;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 「哪个维度的哪一格」的值对象，用作待观察集合与 WARN 限流表的键。
 *
 * <p>不持有 {@link Level} 引用：区块卸载后 Level 对象仍存活，用引用做键会阻止预期回收（ADR-0001）。
 * {@code packedPos} 就是 {@link BlockPos#asLong()}。
 */
public record GlobalPosKey(ResourceKey<Level> dimension, long packedPos) {
    public GlobalPosKey {
        Objects.requireNonNull(dimension, "dimension");
    }

    public static GlobalPosKey of(ResourceKey<Level> dimension, BlockPos pos) {
        return new GlobalPosKey(dimension, pos.asLong());
    }

    public BlockPos pos() {
        return BlockPos.of(this.packedPos);
    }
}
