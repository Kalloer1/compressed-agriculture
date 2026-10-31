# 07: 瓜类模块

**What to build:** 玩家在压缩耕地上种西瓜或南瓜，茎长熟之后果实被自动收进货仓，乘 `9^L`，茎自己退回可再结果的状态接着长。手动右键果实也能收进货仓。

**Blocked by:** 03（方块与货仓，含 `#minecraft:dirt` 标签）

**Status:** ready-for-agent

## 验收标准

### 前提

- [ ] 确认 03 已完成「9 个等级的压缩方块全部加入 `#minecraft:dirt`」（ADR-0010）。不满足则果实放置路径是死代码
- [ ] 确认原版 `StemBlock.randomTick`（`StemBlock.java:93`）的 `FARMLAND || DIRT` 检查在压缩耕地上为 true
- [ ] **确认 `StemBlock.mayPlaceOn`（`StemBlock.java:77`）已被本票的 Mixin 改写**（ADR-0016）。原版只认 `Blocks.FARMLAND`，不读标签；不改写则**瓜种根本种不下去**，后续全部代码永远不会执行

### 识别

- [ ] 方块 `instanceof StemBlock` 一律走本票路径，**不走普通作物路径**（ADR-0009）
- [ ] 果实由 `StemBlock` 的 `fruit` 字段给出，**不硬编码**西瓜 / 南瓜
- [ ] 支持通过 `#compressedagriculture:melon_like_stems` 与 `#compressedagriculture:pumpkin_like_stems` 接入模组茎
- [ ] `L` 取茎下方那个压缩耕地的等级（用 ticket 03 提供的等级读取方法）

### 自动收获

- [ ] Mixin 注入 `StemBlock.randomTick` 与 `performBonemeal`
- [ ] **Mixin 注入 `StemBlock.mayPlaceOn`**，使 `instanceof CompressedFarmlandBlock` 的方块返回 true（ADR-0016）。判据用 `instanceof`，不用方块 ID 列表
- [ ] 用 `@Accessor` Mixin 读取 `private final ResourceKey<Block> fruit`
- [ ] 成功 → 产物进货仓，果实方块不落在世界里，茎不转成 `AttachedStemBlock`

### 失败语义（ADR-0005，与 04 对齐）

- [ ] `INSUFFICIENT_SPACE` → 保留 `AGE=7`、不消耗茎，等重试
- [ ] 与普通作物共用同一套重试逻辑，不各写一份

### 手动兜底（ADR-0008）

- [ ] 玩家右键结果方块时，从四邻域按 **北 → 东 → 南 → 西** 找第一个匹配的茎
- [ ] 取到第一个就立即使用，**不再继续找**
- [ ] 若第一个匹配的茎下方不是压缩耕地，**不跳过、不继续找**——直接判定该结果方块不走压缩路径，放行原版
- [ ] 手动路径同样走 `settle`
- [ ] **明确返回哪个 `ActionResult`**：走压缩路径并成功 → `SUCCESS`（阻止原版继续处理）；判定不走压缩路径 → `PASS`（完全交回原版）。不得返回 `SUCCESS` 同时又不处理

### 种植验证（ADR-0016）

- [ ] 压缩耕地上能正常种下西瓜种 / 南瓜种，茎能长到 `AGE=7`
- [ ] 普通耕地上种瓜行为不受 Mixin 影响

### 交叉验证

- [ ] `minecraft:melon_stem` 种在压缩耕地上、成熟后，只由瓜类路径结算**一次**，不被普通作物路径重复结算（ADR-0009 明确要求的一条测试）

## 备注

- 本票只依赖 03，不依赖 04。虽然 ADR-0005 要求瓜类与普通作物共用重试逻辑，但那部分代码在 08 才落地；本票先保证失败时状态保留正确
- Mixin 注入点靠反编译确认，不写自动化测试（spec Seam 3）

## Further Notes（待定项，不属于验收）

- **模组茎的兼容性**：若模组茎继承 `StemBlock` 且未覆写 `mayPlaceOn`，同一个 Mixin 自动覆盖；若自己实现了种植判断，需各自处理。实现时记录实际遇到的模组茎类型。
