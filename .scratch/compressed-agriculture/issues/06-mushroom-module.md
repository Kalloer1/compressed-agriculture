# 06: 蘑菇模块

**What to build:** 玩家把蘑菇种在压缩草地上，用骨粉一催——原版把蘑菇催成一棵巨大的蘑菇树，然后这棵蘑菇树被整棵拆掉，产物乘 `9^L` 进货仓，原地重新出现一个同种蘑菇，可以接着催。蘑菇的自然扩散保持原版速度和光照要求，不加速。

**Blocked by:** 05（树木模块的枚举与拆除）

**Status:** done

## 验收标准

### 路径一：骨粉催蘑菇树（主要收益）

- [ ] 玩家对压缩草地上的蘑菇用骨粉 → 原版 `growMushroom` 照常执行 —— **headless 未验证，待手工**
- [x] 复用 05 的连通性枚举与拆除逻辑，**不复制一份**（ADR-0013 的「共用引擎」）
- [x] 枚举里 `MUSHROOM_STEM` 归入 `logs`，蘑菇块归入 `leaves`
- [x] 产物乘 `9^L` 调 `settle`，`L` 取蘑菇脚下压缩草地的等级
- [x] 蘑菇树高度 4–6（1/12 概率翻倍至 8–12），拆除要覆盖整个包围盒，不留顶部残块
- [x] 成功 → 原位回填**一个同种蘑菇**（ADR-0015）
- [ ] 骨粉失败（原版约 40% 成功率）时原蘑菇保留，不存在回填问题 —— **headless 未验证，待手工**
- [x] `settle` 失败（货仓满）→ 不拆除、不回填，保留蘑菇树等重试

### 路径二：自然扩散（仅作种子来源）

- [x] 保持原版约束：光照 < 13、9×9×3 内同种 < 5 朵
- [x] **不加速**
- [x] 压缩草地**不加入** `MUSHROOM_GROW_BLOCK` 标签，因此仍受光照约束（ADR-0013）

### 手动兜底

- [x] 玩家手动破坏蘑菇时，产物也被收进货仓（不落地上）
- [x] 手动路径同样走 `settle`，不单开写入分支

### 标签

- [x] `#compressedagriculture:mushrooms` 生效，整合包能通过数据包覆盖以加入自定义蘑菇

## 备注

- 本票是 05 的第一次复用，如果发现 05 的枚举接口不好接，**回头改 05 而不是在 06 里加特例**
- 路径二的扩散不需要额外代码，只是「不加速、不加标签」的负面要求，验收靠确认没做过量的事

## Comments

2026-10-31 · 完工（worker 实现，主会话复核）

- `MushroomBlock.growMushroom` 确实会 fire `BlockGrowFeatureEvent`（`javap` 确认走 `EventHooks.fireBlockGrowFeature`），与树木模块共用同一个事件，只按 `#compressedagriculture:mushrooms` 过滤
- 蘑菇状态必须在**事件里**读：`growMushroom` 会先 `removeBlock(pos)`，成功时不再恢复，tick 末尾读不到「原来种的是红菇还是褐菇」，回填就无从谈起
- 长成判据 = `TreeEnumeration.enumerate` 非空 **且** 树里至少有一根 `mushroom_stem`（防止把恰好立在上方的别的树当成蘑菇树拆掉）
- 拆除与结算全部走 `TreeHarvest.harvest`，没有第二份引擎（ADR-0013）
- 手动兜底：`BlockEvent.BreakEvent`，`settle` 成功才 `setCanceled(true)`；货仓满时不取消，让原版照常掉落（避免「挖了没反应还丢东西」）
- `./gradlew test build` 通过；`runGameTestServer`（先删 `run/gameTestServer/world`）→ **All 8 required tests passed**，含蘑菇树的拆除/回填/货仓断言与手动破坏断言
- 路径二（自然扩散）零代码：未把压缩方块加进 `MUSHROOM_GROW_BLOCK`，未加加速逻辑
