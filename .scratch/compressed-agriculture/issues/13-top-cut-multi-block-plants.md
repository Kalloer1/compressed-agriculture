# 13: 多格植物截顶

**What to build:** 压缩耕地上长出来的多格植物（甘蔗、仙人掌、竹子）**只保留 1 格高度**——一旦第 2 格出现，就把第 2 格拆掉、产物乘 `9^L` 写进脚下压缩耕地的货仓，第 1 格保留继续下一轮。货仓放不下时第 2 格原样保留（**停摆**）。

**Blocked by:** 12（通用地基——没有它这三种植物根本种不到压缩耕地上）

**Status:** ready-for-agent

## 背景与已定决策

- 判定条件 = 「第 2 格与第 1 格**是同一方块**」且「第 1 格属于 `#compressedagriculture:multi_block_plants`」且「第 2 格下方 2 格是压缩耕地」。同方块判定是必须的：否则树苗长成树干时（第 2 格是原木）会被瞬间砍掉，直接和树木模块打架。
- 触发用**通用钩子** `BlockBehaviour#onPlace`（`LevelChunk.setBlockState` 第 283 行对每次方块变更都会调用），一处代码覆盖原版 + 模组的多格植物。**玩家手动往上堆第 2 格也会被拆走**——这是已接受的语义（「这块地上只保留 1 格」）。
- 但**不在 `onPlace` 里当场动手**：`onPlace` 处在 `setBlockState` 内部、邻居更新还没走完，在里面再 `setBlock` 是重入。改为**只记录坐标、`ServerTickEvent.Post` 里统一处理**，沿用仓库现有的「只记录、tick 末处理」先例（骨粉路径 B、树木模块 ADR-0014、蘑菇模块 ADR-0013）。
- 倍率沿用 `settle` 的 `9^L`，不做例外。产物来自第 2 格的原版战利品表（甘蔗/仙人掌/竹子各掉 1 个自身，无实体条件，可以直接查）。

## 验收标准

### 标签

- [ ] 新建 `data/compressedagriculture/tags/block/multi_block_plants.json`，内容：
  ```json
  {"values": ["minecraft:bamboo", "minecraft:cactus", "minecraft:sugar_cane"]}
  ```
- [ ] 标签里**不含**紫颂（紫颂走 ticket 14 的特例规则）、不含树苗/蘑菇（它们长出来的是**不同**方块，由树木/蘑菇模块接管）
- [ ] 代码里通过 `BlockTags` 常量或 `TagKey` 引用该标签，**不得出现任何具体方块 ID**

### 记录层（mixin）

- [ ] 新建 `mixin/BlockBehaviourOnPlaceMixin.java`（名字可自定），`@Inject(method = "onPlace", at = @At("HEAD"))` 注入 `BlockBehaviour#onPlace(BlockState, Level, BlockPos, BlockState, boolean)`，并登记进 `compressedagriculture.mixins.json`
- [ ] 注入体**只做一次廉价判定 + 记录**，不做任何世界修改：
  - `level` 必须是 `ServerLevel`
  - 新方块属于 `#compressedagriculture:multi_block_plants`
  - `pos.below()` 是**同一方块**
  - `pos.below(2)` 是 `CompressedFarmlandBlock`（且带 `CompressedBlockEntity` 货仓）
  - 全部满足才把 `(level, pos)` 塞进本 tick 集合
- [ ] 集合是**每 tick 清空**的 `Set`（`ResourceKey<Level>` + `BlockPos` 或直接 `(ServerLevel, BlockPos)`），线程安全不是问题（服务端主线程）
- [ ] 判定顺序按「最便宜的先判」排列（`instanceof`/标签查询放前面），因为 `onPlace` 会在世界生成时被调用几百万次

### 处理层（tick 末）

- [ ] 新建 `topcut/TopCutModule.java`（包名可自定），注册到 `CompressedAgriculture.java`：`NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, module::onServerTick)`
- [ ] `onServerTick` 遍历本 tick 集合，对每个坐标**重新读实际状态**（不要信任记录时的状态），再次确认「新方块仍是多格植物、下方仍是同方块、下方 2 格仍是压缩耕地」；任一不成立就丢弃这条记录
- [ ] 通过后：查第 2 格的战利品表 → `settle(drops, level, chestTarget)`（`level` 从 `CompressedBlock.levelOf(...)` 读，`chestTarget` 指向 `pos.below(2)` 的货仓）
- [ ] `SUCCESS` → 把第 2 格设为空气（`level.removeBlock(pos, false)` 或 `setBlock(air, 3)`），第 1 格**不动**
- [ ] `INSUFFICIENT_SPACE` → **第 2 格原样保留**，什么都不改，等玩家腾出货仓空间（这就是**停摆**）；打一次 `WARN` 日志带上坐标，但**不要**每 tick 重复刷
- [ ] `INVALID_INPUT`（掉落清单为空）→ 不作处理、不拆除，打 `WARN`（与 ADR-0007 一致）
- [ ] 模块 javadoc 写清楚：为什么用 tick 末而不是当场、为什么只认「同方块」、停摆语义

### GameTest

- [ ] 新建 `topcut/TopCutGameTest.java`，至少覆盖：
  - 甘蔗：1 级压缩耕地上放一格甘蔗 → 随机刻长到第 2 格 → 第 2 格消失、第 1 格还在、货仓里是 **9 的倍数**的甘蔗
  - 手动放置：玩家直接往第 2 格放一格甘蔗 → 同样被拆走并结算
  - 停摆：把货仓塞满 → 第 2 格出现后**不被拆**、货仓不变；腾出空间后（触发下一次 `onPlace` 或等下一次生长）能恢复
  - 反例：普通沙子上的甘蔗长到第 2 格 → **不**被拆（只对压缩耕地生效）
  - 反例：压缩耕地上放一格**不同**方块在第 2 格（例如火把）→ 不被拆
- [ ] 若 GameTest 难以驱动随机刻生长，允许直接 `level.setBlock` 模拟第 2 格出现（这正是 `onPlace` 的触发路径），并在测试注释里写明

### 文档

- [ ] `docs/adr/0021-top-cut-multi-block-plants.md`：新 ADR（0019 已被 `break-keeps-chest-on-item` 占用、0020 已被 ticket 12 的 `universal-substrate` 占用），记录「多格植物只保留 1 格」这条决策，含：为什么用 `onPlace` 而不是 `CropGrowEvent.Post`（后者报的位置不一致：甘蔗报在 `pos.above()`、仙人掌和竹子在底座；且 AGE 增长时也会触发）、为什么必须 tick 末处理、为什么必须同方块判定、停摆语义、以及「手动放置也会被拆」这条已接受的副作用
- [ ] `GLOSSARY.md` 新增三个词（措辞照抄）：
  ```
  **多格植物**（multi-block plant）
  靠自身随机刻在头顶逐格累加同种方块的植物，属于 `#compressedagriculture:multi_block_plants`。默认成员：竹子、仙人掌、甘蔗。

  **截顶**（top-cut）
  多格植物长到第 2 格时，拆掉第 2 格、产物乘 `9^L` 写进货仓的动作。只拆第 2 格，第 1 格保留继续下一轮。

  **停摆**（stalled）
  货仓放不下截顶产物时，第 2 格原样保留、机器暂停，直到玩家腾出货仓空间。
  ```
- [ ] `.scratch/compressed-agriculture/spec.md` 新增一节「多格植物截顶」，写清判定条件、触发链路（`onPlace` 记录 → tick 末处理）、停摆
- [ ] `README.md` 玩法表补一行「多格植物在压缩耕地上只留 1 格，长到第 2 格自动收获」
- [ ] `docs/manual-checklist.md` 补手工验收项：甘蔗/仙人掌/竹子在压缩耕地上永远只有 1 格；手动堆第 2 格会被吃掉；货仓满时停摆

## 完成判据

- [ ] `./gradlew build --no-daemon` 成功
- [ ] `runGameTestServer` 全绿
- [ ] `grep -rn "sugar_cane\|cactus\|bamboo" src/main/java` **零命中**（方块 ID 只能出现在标签 JSON 里）
- [ ] 提交一个 commit

## 备注

- 竹子的 `LEAVES` 属性：竹子生长时会**改下方方块的 `LEAVES` 属性**，拆掉第 2 格后第 1 格可能残留 `LEAVES=SMALL`。不要求处理（视觉细节），但如果一行代码能顺手置回 `NONE` 就做，并在报告里说明。
- 竹子 `STAGE=1` 时生长极快，可能一 tick 内连放多格。规则天然只认「紧贴在植物正上方那一格」，第 3 格出现时 `pos.below(2)` 是植物而不是耕地，不会命中——不需要额外处理。
- 不要为这个模块新增任何「每 tick 全图扫描」的逻辑，集合只由 `onPlace` 填充。
