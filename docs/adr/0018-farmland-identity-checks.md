# 0018 · 原版「这块地是耕地吗」用身份比较，压缩耕地必须逐点扩展

- 状态：已采纳（2026-10-31）
- 影响面：ticket 03（方块属性）、ticket 04（作物模块 Mixin）、ticket 07（瓜类）、ticket 09（水合/形态转换）
- 关系：ADR-0016 是本文在 `StemBlock` 上的一个特例，本文把它推广到全部耕地身份检查

## 背景

spec 用户故事 11：「我想把原版作物种在压缩耕地上，我不需要学习新的种植方式。」
ADR-0010 用「加入 `#minecraft:dirt`」解决了**读标签**的那部分检查。但原版还有一类检查**不读标签，只认方块本身**：

```java
state.is(Blocks.FARMLAND)   // 身份比较，压缩耕地永远为 false
```

压缩耕地是另一个 `Block` 实例，继承 `FarmBlock` 也不会让 `is(Blocks.FARMLAND)` 变 true。

## 证据 A：1.21.1 mojmap 反编译源码（原版行为）

| 位置 | 代码 | 后果 | 处理 |
|---|---|---|---|
| `CropBlock.java:57` `mayPlaceOn` | `return $0.is(Blocks.FARMLAND)` | **小麦/胡萝卜/马铃薯/甜菜根本种不下去** | 必须扩展（ticket 04） |
| `CropBlock.java:120` `getGrowthSpeed` | `if ($$8.is(Blocks.FARMLAND)) { … MOISTURE … }` | 压缩耕地被当成「不是耕地」，生长速度退回 1.0（普通耕地浇水后 3.0） | 必须扩展（ticket 04） |
| `PitcherCropBlock.java:90` `mayPlaceOn` | `return $0.is(Blocks.FARMLAND)` | 瓶子草种不下去 | 必须扩展（ticket 04） |
| `StemBlock.java:77` `mayPlaceOn` | `return $0.is(Blocks.FARMLAND)` | 瓜种种不下去 | 必须扩展（ADR-0016，ticket 07） |
| `BushBlock.java:23` `mayPlaceOn` | `#dirt \|\| FARMLAND` | 树苗/花/蘑菇 | 已由 `#dirt` 覆盖，无需改动 |
| `AttachedStemBlock.java:87` `mayPlaceOn` | `return $0.is(Blocks.FARMLAND)` | 结果茎在压缩耕地上无法存活 | **接受不扩展**：ticket 07 的成功路径不产生 attached stem |
| `Rabbit.java:577` | `$$2.is(Blocks.FARMLAND)` | 兔子不把压缩耕地当农田去啃 | **接受**：兔子啃食不写货仓，扩展反而要额外处理结算 |
| `VillagerProfession.java:39` | `FARMER` 工作站集合含 `FARMLAND` | 村民不把压缩耕地当工作站 | **接受**：村民本来也不会在压缩耕地上种植 |
| `HoeItem.java:29–33` `TILLABLES` | 硬编码五方块表 | 锄头不会把压缩泥土变耕地 | **接受**：由 ticket 09 显式实现「锄头 → 压缩耕地」 |

另外核对：`SpreadingSnowyDirtBlock.randomTick` 的草蔓延目标是 `$$1.getBlockState($$6).is(Blocks.DIRT)`，**同样是身份比较**，所以普通草方块**不会**蔓延覆盖压缩泥土——ADR-0010 里「草蔓延」那条担心的情形实际不会发生，不需要拦截。

## 证据 B：NeoForge 21.1.235 的实际字节码（2026-10-31 用 `javap` 核对 dev 运行期类）

NeoForge 改写了其中几个方法，**注入点必须按实际字节码落，不能按原版源码猜**：

| 方法 | NeoForge 版实际写法 |
|---|---|
| `CropBlock.mayPlaceOn` | `state.getBlock() instanceof FarmBlock`（不再是 `is(Blocks.FARMLAND)`） |
| `PitcherCropBlock.mayPlaceOn` | 同上 |
| `StemBlock.mayPlaceOn` | 同上（ADR-0016 的前提因此需要修正：不是「只认 `Blocks.FARMLAND`」，而是「只认 `FarmBlock` 实例」） |
| `CropBlock.getGrowthSpeed` | 首参从 `Block` 变成 `BlockState`；脚下判定改成 `state.canSustainPlant(...)` 返回 `TriState`，`DEFAULT` 时退回 `instanceof FarmBlock`，为真则再用 `state.isFertile(...)` 决定 1.0 / 3.0。方法体内**已没有** `is(Blocks.FARMLAND)`，剩下的 `is(Block)` 全是「四周是不是同种作物」 |
| `StemBlock.randomTick` | 仍是 `instanceof FarmBlock \|\| is(BlockTags.DIRT)`——ADR-0010 的 `#dirt` 要求**依然成立** |

**关键推论**：`instanceof FarmBlock` 比 `is(Blocks.FARMLAND)` 宽松，但仍然要求方块真的是 `FarmBlock` 的子类。压缩耕地刻意**不继承** `FarmBlock`（`turnToDirt` 会换掉方块、丢掉货仓），所以这些检查对压缩耕地依然全为 false，Mixin 仍然必需。

## 决策

1. 新增 Mixin 扩展点，判据统一用 `instanceof CompressedFarmlandBlock`（不列方块 ID）。**按证据 B 的实际字节码落点**：
   - `CropBlock.mayPlaceOn` → `@Inject(at=RETURN, cancellable)`，原返回 false 且是压缩耕地 → 改 true
   - `PitcherCropBlock.mayPlaceOn` → 同上
   - `CropBlock.getGrowthSpeed` → 两个 `@Redirect`：`canSustainPlant` 对压缩耕地返回 `TriState.TRUE`；`isFertile` 对压缩耕地改读自己的 `MOISTURE > 0`
   - `StemBlock.mayPlaceOn` → 同上（ADR-0016 已定）
   - **不要**去 Redirect `getGrowthSpeed` 里其余的 `is(Block)`：那些是「四周是不是同种作物」，扩展它们会算错生长速度
2. **压缩耕地必须携带 `BlockStateProperties.MOISTURE` 属性**（与 `FarmBlock.MOISTURE` 是同一个属性实例），默认 0。
   原因：`isFertile` 的 Redirect 会 `getValue(MOISTURE)`；若压缩耕地没有该属性，注入后直接 `IllegalStateException`。水合逻辑由 ticket 09 实现，本决策只要求属性先存在。
3. 上表标「接受」的四条不写代码，结论留在本 ADR 与 ticket 03 里。

## 理由

- `is(Blocks.FARMLAND)` 是身份比较，没有任何标签/继承可以绕开；Mixin 是唯一可行手段（与 ADR-0016 同一逻辑）。
- 判据用 `instanceof` 而不是方块 ID 列表，符合「代码里不出现具体方块 ID」的核心原则，也让 18 个方块（2 形态 × 9 等级）自动全部命中。
- 先给压缩耕地 `MOISTURE` 属性，是为了让 `getGrowthSpeed` 的注入**安全**，而不是为了提前实现水合——两者解耦。

## 后果

- ticket 03：`CompressedFarmlandBlock` 带 `MOISTURE` 状态属性（默认 0）；等级读取方法要能从两种形态的 `BlockState` 都读出 `L`。
- ticket 04：新增三个 Mixin（`CropBlock.mayPlaceOn` / `CropBlock.getGrowthSpeed`（两个 Redirect）/ `PitcherCropBlock.mayPlaceOn`），并在游戏内验证「压缩耕地上能种小麦且生长速度与普通耕地一致（未浇水时）」。已实现（2026-10-31），`runServer` 确认两个 Mixin 成功 apply。
- ticket 07：`StemBlock.mayPlaceOn` 的注入判据与本文一致，不再另立标准。
- ticket 09：水合（`MOISTURE` 变化）、僵尸踩踏，都由 09 在自己的方块类里实现，不依赖原版 `FarmBlock`。
