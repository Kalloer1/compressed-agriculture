# 0015 · 蘑菇树拆完后在原位回填一个同种蘑菇

- 状态：已采纳（2026-10-31）
- 影响面：§8、§11.12
- 依赖：ADR-0013（蘑菇树接管）

## 背景

ADR-0013 决定「骨粉催成蘑菇树 → 拆树取产物」。蘑菇树被拆掉之后，原来那格蘑菇已经被 `growMushroom` 消耗掉了：

```java
public boolean growMushroom(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
    ...
    level.removeBlock(pos, false);              // ← 原蘑菇在这里被移除
    if (feature.place(level, generator, random, pos)) { return true; }
    else { level.setBlock(pos, state, 3); return false; }   // 失败才恢复
}
```

成功时原蘑菇不恢复。所以拆完树后，那格是空气（或者是树干的位置，也一起拆了）。

## 决策

拆完蘑菇树后，在**原蘑菇位置**回填一个同种蘑菇。

- 红菇 → `minecraft:red_mushroom`
- 褐菇 → `minecraft:brown_mushroom`
- 模组蘑菇 → 同一个 `Block`（不查 ID，直接用被催熟的那个 `BlockState` 的 `getBlock()`）

## 理由

- 不放回的话，玩家每次催熟都要从容器里摸一个蘑菇出来手动种，才能再来一次。这与 ADR-0014 的树苗回填不一致，也违背「种一次就不用管」。
- 蘑菇树掉落的是 `red_mushroom` / `brown_mushroom` **物品**（0–2 个 × 9^L，写进容器），不是方块实体。地面上那一株被消耗了，需要单独回填。
- 回填的是**蘑菇本身**（`MushroomBlock`），不是蘑菇树的任何方块。这样下一轮还能再次骨粉催熟，形成自循环。

## 后果

- **回填位置必须是原蘑菇位置**（`growMushroom` 传入的 `pos`），不是树干当前位置。蘑菇树的 `place` 原点就是这个 `pos`，树干从这里向上长，所以拆完树后这里通常是空的，可以放回。
- 与 ADR-0014 同样的保护：若原位置已被其他方块占据，**不回填、不覆盖**。
- 与 ADR-0014 同样的问题：回填的蘑菇会立刻被识别为「可催熟目标」，这是**预期行为**（它就是下一轮的种子），但要确保回填动作本身不触发结算。
- 骨粉提示：原版 `isBonemealSuccess` 是 `nextFloat() < 0.4`，即约 40% 成功率。失败的 60% 里 `growMushroom` 不执行，原蘑菇保留，不存在回填问题。
- 蘑菇树的高度是 4–6（1/12 概率翻倍到 8–12），拆除需要覆盖整个包围盒，否则顶部残块会掉落成独立的蘑菇方块。这与 ADR-0011 的树的拆除共用清理逻辑。
