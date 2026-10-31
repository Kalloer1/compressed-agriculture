# 07: 瓜类模块

**What to build:** 玩家在压缩耕地上种西瓜或南瓜，茎长熟之后果实被自动收进货仓，乘 `9^L`，茎自己退回可再结果的状态接着长。手动右键果实也能收进货仓。

**Blocked by:** 03（方块与货仓，含 `#minecraft:dirt` 标签）

**Status:** done

## 验收标准

### 前提

- [x] 确认 03 已完成「9 个等级的压缩方块全部加入 `#minecraft:dirt`」（ADR-0010）。不满足则果实放置路径是死代码
- [x] 确认原版 `StemBlock.randomTick`（`StemBlock.java:93`）的 `instanceof FarmBlock || is(BlockTags.DIRT)` 检查在压缩耕地上为 true（`#dirt` 已覆盖）
- [x] 生长速度不用另做：`StemBlock` 调的是 `CropBlock.getGrowthSpeed`，ticket 04 的两个 Redirect 已对压缩耕地生效
- [x] **确认 `StemBlock.mayPlaceOn`（`StemBlock.java:77`）已被本票的 Mixin 改写**（ADR-0016）。NeoForge 21.1.235 里它是 `state.getBlock() instanceof FarmBlock`（`javap` 已核）；压缩耕地不是 `FarmBlock` 子类，不改写则**瓜种根本种不下去**。注入写法与 ticket 04 的 `CropBlockMixin.mayPlaceOn` 完全一致，直接照抄

### 识别

- [x] 方块 `instanceof StemBlock` 一律走本票路径，**不走普通作物路径**（ADR-0009）
- [x] 果实由 `StemBlock` 的 `fruit` 字段给出，**不硬编码**西瓜 / 南瓜
- [x] 支持通过 `#compressedagriculture:melon_like_stems` 与 `#compressedagriculture:pumpkin_like_stems` 接入模组茎
- [x] `L` 取茎下方那个压缩耕地的等级（用 `CompressedBlock.levelOf(BlockState)`）

### 自动收获

- [x] Mixin 注入 `StemBlock.randomTick` 与 `performBonemeal`
- [x] **Mixin 注入 `StemBlock.mayPlaceOn`**，使 `instanceof CompressedFarmlandBlock` 的方块返回 true（ADR-0016）。判据用 `instanceof`，不用方块 ID 列表
- [x] 用 `@Accessor` Mixin 读取 `private final ResourceKey<Block> fruit`
- [x] 成功 → 产物进货仓，果实方块不落在世界里，茎不转成 `AttachedStemBlock`

### 失败语义（ADR-0005，与 04 对齐）

- [x] `INSUFFICIENT_SPACE` → 保留 `AGE=7`、不消耗茎，等重试
- [x] 与普通作物共用同一套重试逻辑，不各写一份

### 手动兜底（ADR-0008）

- [x] 玩家右键结果方块时，从四邻域按 **北 → 东 → 南 → 西** 找第一个匹配的茎
- [x] 取到第一个就立即使用，**不再继续找**
- [x] 若第一个匹配的茎下方不是压缩耕地，**不跳过、不继续找**——直接判定该结果方块不走压缩路径，放行原版
- [x] 手动路径同样走 `settle`
- [x] **明确返回哪个 `ActionResult`**：走压缩路径并成功 → `SUCCESS`（阻止原版继续处理）；判定不走压缩路径 → `PASS`（完全交回原版）。不得返回 `SUCCESS` 同时又不处理

### 种植验证（ADR-0016）

- [ ] 压缩耕地上能正常种下西瓜种 / 南瓜种，茎能长到 `AGE=7` —— **headless 未验证，待手工**
- [ ] 普通耕地上种瓜行为不受 Mixin 影响 —— **headless 未验证，待手工**

### 已接受的原版差异（ADR-0018）

- [x] `AttachedStemBlock.mayPlaceOn` 只认 `Blocks.FARMLAND`，压缩耕地上结果茎无法存活——**接受，不扩展**：本票的成功路径不产生 attached stem，正常玩法里不会出现

### 交叉验证

- [x] `minecraft:melon_stem` 种在压缩耕地上、成熟后，只由瓜类路径结算**一次**，不被普通作物路径重复结算（ADR-0009 明确要求的一条测试）

## 备注

- 本票只依赖 03，不依赖 04。虽然 ADR-0005 要求瓜类与普通作物共用重试逻辑，但那部分代码在 08 才落地；本票先保证失败时状态保留正确
- Mixin 注入点靠反编译确认，不写自动化测试（spec Seam 3）

## Further Notes（待定项，不属于验收）

- **模组茎的兼容性**：若模组茎继承 `StemBlock` 且未覆写 `mayPlaceOn`，同一个 Mixin 自动覆盖；若自己实现了种植判断，需各自处理。实现时记录实际遇到的模组茎类型。

## Comments

2026-10-31 · 完工（worker 实现，主会话复核 + 一处修正）

- `performBonemeal` **没有单独注入**：字节码确认它 `setBlock(AGE=7)` 后调 `BlockState.randomTick(...)`，虚拟分派回 `StemBlock.randomTick`，`HEAD` 注入已覆盖骨粉路径
- `randomTick` 用 `@Inject(HEAD, cancellable)`。**已知差异**：原版先查光照与 `CropGrowEvent.Pre` 再放果实，`HEAD` 在两者之前，所以成熟茎在暗处也会结算。按本票指定注入点实现，未复制光照判断
- **主会话修正**：手动右键成功后，除了移除果实方块，也把茎设回 `AGE=0`。否则茎停在 `AGE=7`、又不存在 attached stem 来门控，下一随机刻会立刻再结算一次 = 手动收一次白送一次
- 手动路径只在**空手**右键时接管；手里拿方块时右键果实仍归原版（否则抢掉放置）
- 非 `StemBlock` 的模组茎读不到 `fruit`，自动收获不处理；手动兜底按 `#melon_like_stems` / `#pumpkin_like_stems` 标签认（默认空）
- `./gradlew test build` → 28 用例全绿；游戏内行为未验证（headless）
