# 05: 树木模块 — 连通性枚举与 `validateCut`

**What to build:** 玩家把树苗种在压缩泥土上，树照常长成一棵真实的树。然后整棵树被自动拆掉，掉落物乘 `9^L` 进货仓，原地重新出现一棵树苗接着长。产物是原版自己算的——砍一棵橡树给的木棍、苹果、树苗，和你手动砍一模一样，不是手工表推出来的。

这张票的风险是整个项目最高的：`enumerate` 的正确性在 spec 里写着「必须现在写，因为 ADR-0011 是推理出来的、没验证过的」。

**Blocked by:** 03（方块与货仓）

**Status:** ready-for-agent

## 验收标准

### 生长

- [ ] 树苗种在压缩泥土 / 压缩草地上，允许原版 `growTree` 正常执行，**不取消、不拦截**
- [ ] 树苗保持原版两阶段生长节奏，不加速（ADR-0014）
- [ ] 树苗下方是普通泥土时走原版路径，本模组不介入

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

### 结算与拆除

- [ ] 逐位置 `Block.getDrops(state, serverLevel, pos, blockEntity, player, tool)` 取真实掉落
- [ ] 掉落汇总后调 `settle`，`L` 取容器所在格的等级（用 ticket 03 提供的等级读取方法，不自己解析 Block）
- [ ] **单格树 / 蘑菇树：容器 = 树苗（或蘑菇）脚下那个压缩方块**
- [ ] 2×2 巨树：容器按**西北 → 东北 → 西南 → 东南**取第一个存在的 BE（ADR-0004），不要求四格等级一致
- [ ] `settle` 成功后所有枚举出的位置设为空气
- [ ] `settle` 失败时不拆除（保留树，等重试）
- [ ] **不触发** `BreakEvent` / `BlockDropsEvent`——不是玩家破坏，不该加经验、统计、成就
- [ ] 砍树时有木质破坏音效与粒子

### 回填（ADR-0014）

- [ ] 单格树拆完原位放一棵树苗
- [ ] 2×2 巨树拆完四格都放树苗
- [ ] 树苗 `STAGE` 必须置 0，否则会变成无限加速循环
- [ ] 先拆后填的顺序正确，2×2 的四格回填不会被自己的拆除逻辑命中

### GameTest（硬验收，不许跳过）

- [ ] 种一棵橡树苗，`growTree` 后枚举 → `logs` 非空、`leaves` 非空
- [ ] 在树中间砍一格，调 `validateCut` → 拒绝
- [ ] 从树根砍，调 `validateCut` → 通过
- [ ] 给蘑菇树做枚举 → `MUSHROOM_STEM` 进 `logs`，蘑菇块进 `leaves`
- [ ] 枚举结果去重 → 同一位置不出现两次
- [ ] `neoforge.enabledGameTestNamespaces` 设为 `compressedagriculture`

## 备注

- 掉落统计顺序需固定，保证 `settle` 结果可复现
- 蘑菇模块（06）会复用本票的枚举与拆除，所以这部分要设计成可被调用的独立单元，不要写死在树苗事件监听器里
- 参考源码：`E:/mods/other_mod/Create/src/main/java/com/simibubi/create/content/kinetics/saw/TreeCutter.java`

## Further Notes（待定项，不属于验收）

- **`validateCut` 的精确判据**：从树苗位置调还是从树干位置调？Create 的实现依赖它的 `pos` 语义，需读源码后定。定完后回写本票。
- **未加载区块的处理**：树可能跨区块，`ServerLevel.getBlockState` 在未加载区块上返回空气。跳过还是强制加载？倾向跳过并记录被跳过的坐标，需实测确认不会漏掉整棵树的大部分。
- **回填与拆除的顺序**：ADR-0014 要求先拆后填，2×2 的四格回填需确认不会被自身拆除逻辑命中。
