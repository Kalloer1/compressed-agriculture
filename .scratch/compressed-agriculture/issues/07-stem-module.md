# 07: 瓜类模块

**What to build:** 玩家在压缩耕地上种西瓜或南瓜，茎长熟之后果实被自动收进货仓，乘 `9^L`，茎自己退回可再结果的状态接着长。手动右键果实也能收进货仓。

**Blocked by:** 03（方块与货仓，含 `#minecraft:dirt` 标签）

**Status:** ready-for-agent

## 验收标准

### 前提

- [ ] 确认 03 已完成「9 个等级的压缩方块全部加入 `#minecraft:dirt`」（ADR-0010）。不满足则整条路径是死代码，本票无法开始
- [ ] 确认原版 `StemBlock.randomTick` 的 `FARMLAND || DIRT` 检查在压缩耕地上为 true

### 识别

- [ ] 方块 `instanceof StemBlock` 一律走本票路径，**不走普通作物路径**（ADR-0009）
- [ ] 果实由 `StemBlock` 的 `fruit` 字段给出，**不硬编码**西瓜 / 南瓜
- [ ] 支持通过 `#compressedagriculture:melon_like_stems` 与 `#compressedagriculture:pumpkin_like_stems` 接入模组茎
- [ ] `L` 取茎下方那个压缩耕地的等级

### 自动收获

- [ ] Mixin 注入 `StemBlock.randomTick` 与 `performBonemeal`
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

### 交叉验证

- [ ] `minecraft:melon_stem` 种在压缩耕地上、成熟后，只由瓜类路径结算**一次**，不被普通作物路径重复结算（ADR-0009 明确要求的一条测试）

## 备注

- 本票只依赖 03，不依赖 04。虽然 ADR-0005 要求瓜类与普通作物共用重试逻辑，但那部分代码在 08 才落地；本票先保证失败时状态保留正确
- Mixin 注入点靠反编译确认，不写自动化测试（spec Seam 3）
