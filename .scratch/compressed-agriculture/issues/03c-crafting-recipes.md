# 03c: 合成配方

**What to build:** 9 个泥土 → 1 个 1 级压缩泥土；9 个低级方块 → 1 个高级方块，容量档位相加、内容清空。

**Blocked by:** 03（方块与货仓）

**Status:** done

## 验收标准

### 基础配方

- [x] 9 泥土 → 1 级压缩泥土（普通 JSON 配方，`data/compressedagriculture/recipe/`，1.21.1 目录名是 `recipe` 不是 `recipes`）
- [x] 结果物品不带任何货仓数据组件（干净的 L1 方块）

### 9 合 1 升级配方

- [x] 2–9 级：9 个同形态、等级 `n-1` 的压缩方块 → 1 个等级 `n` 的同形态方块
- [x] `chestCount` 用 `Math.addExact` 累加 9 个输入的值
- [x] 累加结果 > `ChestCapacity.MAX_CHEST_COUNT` → **配方不匹配**（不消耗材料、不产出）
- [x] 匹配成功时**内容物清空**，只保留累加后的 `chestCount`
- [x] 三种形态各自独立成链，不跨形态合成（9 个压缩泥土不能合成压缩草地）
- [x] 输入顺序无关：9 个格子任意摆放都能匹配
- [x] 少放一个（8 个）不匹配；混入一个其他等级的方块不匹配
- [x] 实现方式：自定义 `CustomRecipe` / `SpecialCraftingRecipe`（1.21.1 先查反编译源码确认基类、`matches(CraftingInput, Level)` 与 `assemble(CraftingInput, HolderLookup.Provider)` 的签名、`RecipeSerializer` 注册方式），不要凭记忆写
- [x] 配方书行为：`CustomRecipe.isSpecial()` 为 true → **不进配方书**（原版所有 special 配方同理），不崩。已接受

## 备注

- 扩容配方（`chestCount + 1`）留给 08，因为它是 GUI 的验收面
- 形态转换（泥土 ↔ 草地 ↔ 耕地）留给 09
- 需要 `03b` 的数据搬运能力才能把 `chestCount` 写进产物物品；若 03b 未完成，本票只能产出 `chestCount = 0` 的方块，请在开工前确认依赖顺序（建议 03b 先完成）
- 单元测试：`CustomRecipe` 的 `matches` 逻辑依赖 `CraftingInput`（MC 类，可能触发 bootstrap），能测就测；不能测就在报告里说明并用「代码审查 + 数据包结构校验」代替

## Comments

2026-10-31 · 完工（worker 实现，主会话复核）

- 25 个 JSON：`compressed_dirt_1.json`（9 泥土 → L1，`crafting_shaped`，key 用**具体物品** `minecraft:dirt` 而不是 `#minecraft:dirt`——27 个压缩方块都在那个标签里，用标签会让 9 个压缩泥土换回 1 个干净 L1，直接丢货仓）+ 24 个 `compressed_{dirt,grass,farmland}_{2..9}.json`
- 匹配与产物构造共用同一个 `chestCountSum(CraftingInput) -> OptionalLong`，不存在两套判定
- `ChestStorage.TAG_CHEST_COUNT` 从 private 提到 public，配方读物品组件时复用同一常量
- `./gradlew test build` 通过；`runGameTestServer`（先删世界）→ **21/21 全绿**（新增 6 条：累加 12、8 个不匹配、混等级不匹配、超 `MAX_CHEST_COUNT` 不匹配、内容清空、产物无脏数据）
- 未验证（headless）：真实工作台界面里的合成、配方书
