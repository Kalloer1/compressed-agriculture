# 11: 删除压缩草地形态

**What to build:** 把「三形态」砍成「两形态」——压缩泥土 + 压缩耕地。删掉 `CompressedForm.GRASS`、9 个草地 Block、草蔓延逻辑、全部草地资源与配方，同步脚本与文档。压缩方块总数 27 → 18，升级配方 24 → 16。

**Blocked by:** None (can start immediately)

**Status:** done

## 背景与已定决策

- 草地在当前代码里只剩三件职责：**草蔓延**（`CompressedGrassBlock.randomTick`）、**染色/渲染层**、**`#minecraft:dirt` 标签成员资格**。
- 蘑菇/树木/作物三个模块全都只认 `CompressedBlock` + 货仓，**从不检查 `form()`**，所以删草地不影响它们。
- 草地作为「蘑菇地基」是**文档要求、代码从未实现**（`MushroomModule` 只查 `instanceof CompressedBlock`）。删掉后蘑菇地基改为压缩耕地（由 ticket 12 的通用地基承接）。
- `compressed_grass_1` **没有任何配方**（`compressed_grass_2..9` 有升级配方，1 级没有来源），所以生存模式里本来只能靠创造模式标签页取得——删除代价极低。
- 模组版本仍是 `1.0.0`、从未发布，**不做 datafixer**：旧存档里的压缩草地方块会变成缺失方块。这是唯一的数据丢失面，接受。

## 验收标准

### Java

- [x] 删除整文件 `src/main/java/com/kalloer/compressedagriculture/block/CompressedGrassBlock.java`
- [x] `block/CompressedForm.java`：删 `GRASS` 常量，类注释从「三种形态」改为「两种形态」（`FARMLAND` 的 ordinal 会从 2 变 1——已核对全仓库无 `ordinal()` 调用、形态不进 NBT，无风险）
- [x] `registry/ModBlocks.java`：删 `COMPRESSED_GRASS` 注册表、注册循环里的 grass 分支、创造模式标签页里的草地条目；相关注释一并改
- [x] `block/FormConversionModule.java`：逻辑本来就形态无关（`form() == DIRT` 就 return），只需改注释里提到「压缩草地」的地方
- [x] 删除整文件 `client/CompressedAgricultureClientEvents.java`（整个文件只服务草地染色），并从 `CompressedAgricultureClient.java` 删掉对应的 `RegisterColorHandlersEvent` 监听与 import
- [x] `mixin/FeatureMixin.java` **不动**（它拦的是树木「换土」把压缩方块换成灰化土，与草地形态无关）
- [x] `src/jei/java/**` **不动**（JEI 插件全仓库 grep 无 grass 命中）

### 资源（37 个文件/条目）

- [x] 删 9 个 blockstate：`assets/compressedagriculture/blockstates/compressed_grass_1..9.json`
- [x] 删 1 个方块模型：`assets/.../models/block/compressed_grass.json`
- [x] 删 9 个物品模型：`assets/.../models/item/compressed_grass_1..9.json`
- [x] 删 9 个战利品表：`data/compressedagriculture/loot_table/blocks/compressed_grass_1..9.json`
- [x] 删 8 个升级配方：`data/compressedagriculture/recipe/compressed_grass_2..9.json`
- [x] 删 `lang/en_us.json` 与 `lang/zh_cn.json` 里 9 行草地译名（两个文件各 9 行，位置相邻）
- [x] `data/minecraft/tags/block/dirt.json`：删 9 行 `compressedagriculture:compressed_grass_<n>`，**保留** dirt 9 行 + farmland 9 行（共 18 行）
- [x] **没有自定义贴图**，不需要删贴图（草地模型 `parent` 指向 `minecraft:block/grass_block`）

### 资源生成脚本（漏了会复活草地）

- [x] `TMP/scripts/generate_resources.ps1`、`gen-recipes.ps1`、`generate_loot_tables.ps1`、`generate_tags.ps1` 四个脚本里的 `dirt, grass, farmland` 三形态硬编码全部改成两形态
- [x] 改完后**不要重跑**脚本（资源已手工删干净）；只在注释里说明脚本已同步

### GameTest

- [x] `block/FormConversionGameTest.java`：删掉 `grassSpreadsOntoNeighbourKeepingChest`（原 82-128 行）整条用例，类 javadoc 从「三条用例」改为「两条」
- [x] `mushroom/MushroomHarvestGameTest.java`：第 37、90 行的 `ModBlocks.COMPRESSED_GRASS.get(0)` 改为 `COMPRESSED_DIRT`，第 35 行 javadoc「长在 1 级压缩草地上」改为「压缩泥土」
- [x] `recipe/CompressedExpansionRecipeGameTest.java`：第 104、111 行把草地换成压缩泥土
- [x] `recipe/CompressedUpgradeRecipeGameTest.java`：第 116-117 行的「另一形态」改用 `COMPRESSED_FARMLAND`；第 147 行 `List.of("dirt", "grass", "farmland")` 改为 `List.of("dirt", "farmland")`，配方总数断言 24 → 16
- [x] `tree/AlterGroundProtectionGameTest.java` **不动**（它测的是压缩泥土 + 原版 `Blocks.GRASS_BLOCK`，注释里的「草方块」指原版草方块）

### 文档

- [x] `GLOSSARY.md`：第 8 行「分三形态…共 27 个 Block（3 形态 × 9 等级）」→ 两形态 / 18 个 Block；第 11 行「形态」词条「三种互转状态之一：普通 / 草地 / 耕地」→「两种：泥土 / 耕地」
- [x] `README.md` 第 11 行玩法表：「3 形态（泥土 / 草地 / 耕地）× 9 等级 = 27 个方块」→ 两形态 / 18
- [x] `.scratch/compressed-agriculture/spec.md`：第 27 行方案总述、第 50 行用户故事 4（「把压缩泥土变成压缩草地」——该工具路径本来就不存在，整条删掉）、第 75 行用户故事 23（删掉「压缩草地」）、第 89 行用户故事 34（蘑菇地基改为压缩耕地）、第 233 行「压缩草地不加入 `MUSHROOM_GROW_BLOCK`」改为「压缩方块不加入」
- [x] `docs/adr/0013-mushroom-huge-tree-harvest.md`：第 8、61、72、76、87、106-107 行里「压缩草地」改为「压缩耕地」；第 87 行那句「蘑菇下方的压缩方块必须是压缩草地」整句改写为「下方的压缩方块必须是压缩耕地（ticket 12 的通用地基保证蘑菇在耕地上能活）」
- [x] `docs/adr/0010-compressed-farmland-must-be-in-dirt-tag.md`：风险表里「草蔓延（grass_block 蔓延到泥土）」一行改为结论「无风险：`SpreadingSnowyDirtBlock.randomTick` 用的是硬编码 `is(Blocks.DIRT)`，不读标签」；第 56 行「蘑菇可放置」一行改为「压缩耕地」
- [x] `docs/adr/0018-farmland-identity-checks.md` 第 32 行：删掉「压缩方块之间的草蔓延由 ticket 09 自己在 `randomTick` 里实现」半句
- [x] `docs/manual-checklist.md`：第 9 行 tintindex 项删掉；第 19 行改为「压缩泥土/压缩耕地上能种树苗」；第 47 行改为「铲子右键压缩耕地 → 压缩泥土」；第 48 行草蔓延项删掉
- [x] `docs/plan-v2-changes.md` 第 129 行「压缩草地」改为「压缩耕地」
- [x] 历史 ticket（03 / 03c / 09）里的「三形态」记录**保留不改**（它们是历史验收记录，不是当前规格）

## 完成判据

- [x] `grep -ri "compressed_grass\|CompressedForm\.GRASS\|COMPRESSED_GRASS\|CompressedGrassBlock"` 在 `src/`、`docs/`、`README.md`、`GLOSSARY.md`、`TMP/scripts/` 下**零命中**（历史 ticket 目录除外）
- [x] `JAVA_HOME=D:\tools\jdk21 ./gradlew build --no-daemon` 成功
- [x] `runGameTestServer` 全绿，基线 23/23（原 24，删 1 条）
- [x] `ModBlocks` 注册的压缩方块总数 = 18，升级配方 = 16
- [x] 提交一个 commit

## 备注

- 工作树里可能残留上一轮的未提交改动，**动手前先 `git status`**，别把无关改动混进这个 commit。
- 构建环境（JDK、代理白名单、`--no-build-cache` 适用场景）见 `E:/mods/AGENTS.md`，不要重新摸索。
- 若遇到 Gradle 锁（多条线并行构建），放弃构建、先交报告，由主会话统一构建。

## 执行记录（commit 8b50eea）

验证：`./gradlew build --no-daemon` 成功（含 50 条 JUnit）；`runGameTestServer` **61/61 全绿**。

**GameTest 基线与票不符**：票写的 23/23 是旧状态。提交树里 `@GameTest` 共 33 条（原 34 − 删掉的草蔓延用例），工作树里 61 条——多出的 28 条来自工作区里**未提交的前序工作**。

**未按票逐行执行 / 与票描述不符的地方**：

1. `registry/ModCreativeTabs.java` 没有「草地条目」可删——标签页遍历 `ALL_COMPRESSED`，天然跟着变。只改了注释里的 27 → 18。
2. `recipe/CompressedExpansionRecipeGameTest.java` 的草地→泥土改动落在**前序未提交版本**里（HEAD 里没有 `trappedChestIsAccepted` 用例），所以该文件整体没进 commit，改动只存在于工作区。
3. `CompressedAgricultureClient.java` 的染色监听是前序未提交工作加的，删掉后该文件相对 HEAD 零差异 → commit 里不含它。`client/CompressedAgricultureClientEvents.java` 从未被 commit（untracked），删除不产生 git 记录。
4. `lang/zh_cn.json` 是前序未提交的新文件（untracked）：9 行草地译名已在工作区删除，但文件本身不在 commit 里。
5. `TMP/scripts/` 四个脚本已同步为两形态，但 `TMP/` 被 `.gitignore` 忽略 → 不进 commit，只在工作区生效。
6. 额外同步了票没逐行点名、但被本次删除「证伪」的计数/表述：`CompressedBlock` / `ModBlockEntities` / `CompressedBlockEntity` / `ModCreativeTabs` / `CropBlockMixin` / `CompressedAgriculture` / `MushroomModule` 的注释，`docs/adr/0002` / `0006` / `0018`（27 方块、三种形态、草蔓延）、`docs/plan-v2.0.md`、`docs/manual-checklist.md` 的 27 方块计数、`spec.md` 第 137 行。
7. `spec.md` 用户故事 4 整行删掉后，源码编号从 3 跳到 5（Markdown 渲染仍连续），**没有**给后面 38 条重新编号，避免制造大 diff。
8. `src/jei/java/**` 按票不动，但两处注释仍写着「27 个压缩方块物品（3 形态 × 9 等级）」，已过期。
