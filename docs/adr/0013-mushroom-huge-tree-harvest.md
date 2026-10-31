# 0013 · 蘑菇模块走「骨粉催成蘑菇树 → 拆树收产物」，扩散路径仅保留原版约束

- 状态：已采纳（2026-10-31）
- 影响面：§8 蘑菇、§11.12

## 背景

方案 §8 只描述了蘑菇的**扩散**（随机刻长出新蘑菇），以及「破坏蘑菇时若下方是压缩草地则写入容器」。讨论中曾质疑：「照原版约束的话，压缩农业对蘑菇没有任何增益，这个模块存在意义只剩自动收获」。

这个质疑忽略了蘑菇模块的另一半：**骨粉把蘑菇催成一棵完整的蘑菇树**。

## 事实（已验证）

`MushroomBlock` 实现 `BonemealableBlock`：

```java
public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
    this.growMushroom(level, pos, state, random);
}

public boolean growMushroom(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
    ... registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolder(this.feature) ...
    level.removeBlock(pos, false);
    if (feature.place(level, chunkSource.getGenerator(), random, pos)) { return true; }
    else { level.setBlock(pos, state, 3); return false; }
}
```

`isBonemealSuccess` 为 `nextFloat() < 0.4`。

蘑菇树是**世界生成结构**，不是随机刻产物：

| | 红菇 | 褐菇 |
|---|---|---|
| `foliage_radius` | 2 → 顶盖 5×5 | 3 → 顶盖 7×7 |
| 树干材料 | `mushroom_stem` | `mushroom_stem` |
| 顶盖材料 | `red_mushroom_block` | `brown_mushroom_block` |

树高 `getTreeHeight` = `nextInt(3) + 4`（即 4–6），并有 `nextInt(12) == 0` 时**翻倍**到 8–12。

所以一棵蘑菇树是 20–60+ 个方块的实体结构，与一棵橡树同量级甚至更大。

## 产物语义（已验证）

蘑菇方块**默认不掉落自身**，只有精准采集才掉方块本身。普通破坏的掉落是：

| 方块 | 无精准采集 | 有精准采集 |
|---|---|---|
| `red_mushroom_block` | `red_mushroom` × 0–2 | `red_mushroom_block` × 1 |
| `brown_mushroom_block` | `brown_mushroom` × 0–2 | `brown_mushroom_block` × 1 |
| `mushroom_stem` | `mushroom_stem` × 1（需精准采集） | 同左 |

也就是说：**拆一棵蘑菇树，产出的是几十个蘑菇物品**。这一份量乘以 `9^L` 正是蘑菇模块的价值所在。

## 决策

蘑菇模块有**两条独立的收益路径**，都要实现：

### 路径一：骨粉催蘑菇树（主要收益）

1. 玩家对压缩草地上的蘑菇使用骨粉；
2. **不做任何拦截**，让原版 `performBonemeal` 正常执行 `growMushroom`；
3. 蘑菇树生成完成后，按 ADR-0011 的同一套「记录这次生成了哪些方块 → 逐方块取真实掉落」逻辑结算；
4. 拆除整棵蘑菇树，`× 9^L` 写入容器。

这条路径与 ADR-0011 的树木路径**共用同一个「真实结构生成 + 记录 + 拆除 + 结算」引擎**。蘑菇树和普通树在实现上是同一个问题的两个实例，不应该写两份。

### 路径二：随机扩散（仅保留原版约束）

蘑菇的随机刻扩散完全按原版：光照 < 13（除非下方是菌丝/灰化土/菌岩）、9×9×3 内同种 < 5 朵。不加速、不限流。

扩散出的新蘑菇同样可以在压缩草地上被骨粉催成蘑菇树，所以这条路径是路径一的**种子来源**，不是独立收益。

### 手动收获

破坏蘑菇时若下方是压缩草地，写入容器（§8 原文）。

## 为什么保留原版扩散约束

- 扩散速度是原版的平衡设计，加速会让蘑菇变成无限种子机——玩家可以靠一小片蘑菇不断扩散 + 催熟获得无上限产出，与「压缩」的「用面积换产量」的定位冲突。
- 保留约束不需要额外代码，反而省事。原版逻辑已经写好了。

## 后果

- §8 必须重写：现在只写了扩散与手动收获，没有骨粉催蘑菇树这条主要收益路径。
- §11.12 的任务描述需要从「蘑菇标签模块 + 光照/数量条件」扩展为「蘑菇标签 + 扩散约束 + 骨粉催树接管」。
- 蘑菇下方的压缩方块必须是**压缩草地**（§8 原文），不是压缩泥土或压缩耕地。
- **可种植性已验证**：`MushroomBlock extends BushBlock`，其 `canSurvive` 走 `BushBlock.mayPlaceOn`：

  ```java
  protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
      return state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND);
  }
  ```

  因为 ADR-0010 已要求压缩方块加入 `#minecraft:dirt`，它们**天然满足**这道检查，不需要额外工作。两条路子都通：要么进 `#dirt`，要么另行覆盖。

  但需注意另一条：`canSurvive` 的完整写法是

  ```java
  return below.is(BlockTags.MUSHROOM_GROW_BLOCK)
      ? true
      : level.getRawBrightness(pos, 0) < 13 && this.mayPlaceOn(below, level, belowPos);
  ```

  `MUSHROOM_GROW_BLOCK` 只含 `mycelium` / `podzol` / `crimson_nylium` / `warped_nylium`，压缩草地不在其中。因此**压缩草地上的蘑菇仍受光照 < 13 约束**，不能像菌丝那样无视光照。若希望压缩草地等同于菌丝，需把它加入 `MUSHROOM_GROW_BLOCK`——**本 ADR 不这么做**，理由见下。
- 不把压缩草地加进 `MUSHROOM_GROW_BLOCK`：那个标签的语义是「能无视光照长蘑菇的菌类土壤」。压缩草地是草地，视觉和语义上都不该获得菌丝特权。玩家想无视光照种蘑菇，把压缩草地放在菌丝旁边即可。
- 蘑菇树的拆除必须干净：`mushroom_stem` 与顶盖方块都有 `HugeMushroomBlock` 的六面连接属性，拆一半会留下视觉残块。
