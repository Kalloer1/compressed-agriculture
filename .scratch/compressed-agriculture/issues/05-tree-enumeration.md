# 05: 树木枚举与 `validateCut`（纯逻辑 + GameTest）

**What to build:** 把「一棵树有哪些方块」这件事做成一个独立、可被调用的纯逻辑单元：从树苗位置向上找到树干，BFS 连通性遍历，结果分成 `logs` / `leaves` / `attachments` 三类，外加 `validateCut` 防止把树的中段当成整棵。**本票不接线任何事件、不结算、不拆除、不回填**——那些是 `05b`。

这张票的风险是整个项目最高的：`enumerate` 的正确性在 spec 里写着「必须现在写，因为 ADR-0011 是推理出来的、没验证过的」。所以本票用 **GameTest** 做硬验收，必须真跑 `./gradlew runGameTestServer`。

**Blocked by:** 03（方块与货仓）

**Status:** done

## 验收标准

### 枚举（ADR-0011，参考 Create `TreeCutter`）

- [x] 从树苗位置向上找到树干，BFS 连通性遍历，结果分三类：`logs` / `leaves` / `attachments`
- [x] `logs` 判据：`BlockTags.LOGS` / `BlockTags.SLIMY_LOGS` / `MUSHROOM_STEM`
- [x] `leaves` 判据：**任何拥有 `distance` 整数属性**的方块（排除 `STABILITY_DISTANCE`）；蘑菇块 / 菌岩按固定半径红菇 2、褐菇 3
- [x] `attachments` 判据：`TreeAttachments` 类标签
- [x] 用 `visited` 集合保证每个位置只进一次，结果**无重复**
- [x] **不设人为方块数上限**（ADR-0011 / ADR-0012）。若实测卡顿，改限定枚举区块范围，不砍方块数

### `validateCut`

- [x] 从树根砍 → 返回可以砍
- [x] 从树中间砍 → 拒绝（防止上半截被当成整棵树拆掉）

### GameTest（硬验收，不许跳过）

- [x] 种一棵橡树苗，`growTree` 后枚举 → `logs` 非空、`leaves` 非空
- [x] 在树中间砍一格，调 `validateCut` → 拒绝
- [x] 从树根砍，调 `validateCut` → 通过
- [x] 给蘑菇树做枚举 → `MUSHROOM_STEM` 进 `logs`，蘑菇块进 `leaves`
- [x] 枚举结果去重 → 同一位置不出现两次
- [x] `neoforge.enabledGameTestNamespaces` 设为 `compressedagriculture`
- [x] `./gradlew runGameTestServer` 真的跑过，且上述用例全绿（模板自带该 run 配置）

## 备注

- 蘑菇模块（06）会复用本票的枚举，所以必须设计成可被调用的独立单元，不要写死在事件监听器里
- 接口建议（05b / 06 会依赖，名字可微调但要在票里写死）：
  ```java
  public final class TreeEnumeration {
      public static Result enumerate(ServerLevel level, BlockPos saplingPos);   // 从树苗位置找树干再 BFS
      public record Result(Set<BlockPos> logs, Set<BlockPos> leaves, Set<BlockPos> attachments) {}
      public static boolean validateCut(ServerLevel level, BlockPos cutPos);
  }
  ```
- 掉落统计顺序需固定（`LinkedHashSet` / 排序后的 `List`），保证 `settle` 结果可复现
- 参考源码：`E:/mods/other_mod/Create/src/main/java/com/simibubi/create/content/kinetics/saw/TreeCutter.java`

## Further Notes（待定项，不属于验收）

- **`validateCut` 的精确判据（已定）**：`validateCut(level, cutPos)` = 「从 `cutPos` 出发、只沿连通原木 BFS，是否存在 Y 更低的原木」。存在 → 拒绝（`false`）。树根不算（红树的根在树干下方）。调用方传**要砍的那一格**，不是树苗位置。
- **未加载区块的处理（已定）**：按空气处理，不强行加载（`TreeEnumeration.stateAt`）。`findTrunk` 遇到未加载区块直接返回「没有树」。
- **回填与拆除的顺序**：留给 `05b`（ADR-0014 要求先拆后填）。

## Comments

2026-10-31 · 拆分说明

- 原 ticket 05 拆成两张：**05（本票）= 枚举 + validateCut + GameTest**，**05b = 事件接线 + 结算 + 拆除 + 回填 + 音效**。
  理由：单张票要在一个 worker 运行内完成「枚举正确性 + GameTest + 事件 + 拆除 + 回填」，实测会撞运行时限
- 本票不碰世界状态（只读 + GameTest 内部造结构），05b 才写方块

## Comments

2026-10-31 · 完工（worker 实现，主会话复核）

- `./gradlew runGameTestServer` → **All 5 required tests passed**（枚举原木+树叶 / 原版真长一棵橡树再枚举 / validateCut 根可砍中段拒绝 / 蘑菇树 / 去重与可复现）
- ⚠ **跑 GameTest 前必须删掉 `run/gameTestServer/world`**。上一次运行被强杀会留下半初始化的世界，下一次会卡在 batch 永不结束（表现为进度条一直 `[ ++++]`、日志每秒一条 `Gathered mod list`）。删掉世界后 24 秒跑完
- **两个标签是自建的**：1.21.1 原版没有 `BlockTags.SLIMY_LOGS`，NeoForge 也没有 `AllBlockTags.TREE_ATTACHMENTS`（两者都是 Create 的自有标签）。本模组在 `#compressedagriculture:slimy_logs` / `tree_attachments` / `tree_roots`（默认 `mangrove_roots`）里自建，接口与 ADR-0011 一致
- 结构模板：`data/compressedagriculture/structure/empty.nbt`（16×16×16 空结构，脚本 `TMP/gen-empty-structure.ps1` 生成）
- 枚举规则：树干从树苗向上找到最低原木；原木 3×3×2 盒向 UP 扩散；有根时向 DOWN 扩；树叶按 `distance` 严格递增扩散，蘑菇块按水平半径 2/3；`attachments` 不进 frontier
