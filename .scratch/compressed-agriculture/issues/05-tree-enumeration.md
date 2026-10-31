# 05: 树木枚举与 `validateCut`（纯逻辑 + GameTest）

**What to build:** 把「一棵树有哪些方块」这件事做成一个独立、可被调用的纯逻辑单元：从树苗位置向上找到树干，BFS 连通性遍历，结果分成 `logs` / `leaves` / `attachments` 三类，外加 `validateCut` 防止把树的中段当成整棵。**本票不接线任何事件、不结算、不拆除、不回填**——那些是 `05b`。

这张票的风险是整个项目最高的：`enumerate` 的正确性在 spec 里写着「必须现在写，因为 ADR-0011 是推理出来的、没验证过的」。所以本票用 **GameTest** 做硬验收，必须真跑 `./gradlew runGameTestServer`。

**Blocked by:** 03（方块与货仓）

**Status:** ready-for-agent

## 验收标准

### 枚举（ADR-0011，参考 Create `TreeCutter`）

- [ ] 从树苗位置向上找到树干，BFS 连通性遍历，结果分三类：`logs` / `leaves` / `attachments`
- [ ] `logs` 判据：`BlockTags.LOGS` / `BlockTags.SLIMY_LOGS` / `MUSHROOM_STEM`
- [ ] `leaves` 判据：**任何拥有 `distance` 整数属性**的方块（排除 `STABILITY_DISTANCE`）；蘑菇块 / 菌岩按固定半径红菇 2、褐菇 3
- [ ] `attachments` 判据：`TreeAttachments` 类标签
- [ ] 用 `visited` 集合保证每个位置只进一次，结果**无重复**
- [ ] **不设人为方块数上限**（ADR-0011 / ADR-0012）。若实测卡顿，改限定枚举区块范围，不砍方块数

### `validateCut`

- [ ] 从树根砍 → 返回可以砍
- [ ] 从树中间砍 → 拒绝（防止上半截被当成整棵树拆掉）

### GameTest（硬验收，不许跳过）

- [ ] 种一棵橡树苗，`growTree` 后枚举 → `logs` 非空、`leaves` 非空
- [ ] 在树中间砍一格，调 `validateCut` → 拒绝
- [ ] 从树根砍，调 `validateCut` → 通过
- [ ] 给蘑菇树做枚举 → `MUSHROOM_STEM` 进 `logs`，蘑菇块进 `leaves`
- [ ] 枚举结果去重 → 同一位置不出现两次
- [ ] `neoforge.enabledGameTestNamespaces` 设为 `compressedagriculture`
- [ ] `./gradlew runGameTestServer` 真的跑过，且上述用例全绿（模板自带该 run 配置）

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

- **`validateCut` 的精确判据**：从树苗位置调还是从树干位置调？Create 的实现依赖它的 `pos` 语义，需读源码后定。定完后回写本票。
- **未加载区块的处理**：树可能跨区块，`ServerLevel.getBlockState` 在未加载区块上返回空气。跳过还是强制加载？倾向跳过并记录被跳过的坐标，需实测确认不会漏掉整棵树的大部分。
- **回填与拆除的顺序**：ADR-0014 要求先拆后填，2×2 的四格回填需确认不会被自身拆除逻辑命中。

## Comments

2026-10-31 · 拆分说明

- 原 ticket 05 拆成两张：**05（本票）= 枚举 + validateCut + GameTest**，**05b = 事件接线 + 结算 + 拆除 + 回填 + 音效**。
  理由：单张票要在一个 worker 运行内完成「枚举正确性 + GameTest + 事件 + 拆除 + 回填」，实测会撞运行时限
- 本票不碰世界状态（只读 + GameTest 内部造结构），05b 才写方块
