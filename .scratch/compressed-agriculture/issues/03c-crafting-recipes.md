# 03c: 合成配方

**What to build:** 9 个泥土 → 1 个 1 级压缩泥土；9 个低级方块 → 1 个高级方块，容量档位相加、内容清空。

**Blocked by:** 03（方块与货仓）

**Status:** ready-for-agent

## 验收标准

### 基础配方

- [ ] 9 泥土 → 1 级压缩泥土（普通 JSON 配方，`data/compressedagriculture/recipe/`，1.21.1 目录名是 `recipe` 不是 `recipes`）
- [ ] 结果物品不带任何货仓数据组件（干净的 L1 方块）

### 9 合 1 升级配方

- [ ] 2–9 级：9 个同形态、等级 `n-1` 的压缩方块 → 1 个等级 `n` 的同形态方块
- [ ] `chestCount` 用 `Math.addExact` 累加 9 个输入的值
- [ ] 累加结果 > `ChestCapacity.MAX_CHEST_COUNT` → **配方不匹配**（不消耗材料、不产出）
- [ ] 匹配成功时**内容物清空**，只保留累加后的 `chestCount`
- [ ] 三种形态各自独立成链，不跨形态合成（9 个压缩泥土不能合成压缩草地）
- [ ] 输入顺序无关：9 个格子任意摆放都能匹配
- [ ] 少放一个（8 个）不匹配；混入一个其他等级的方块不匹配
- [ ] 实现方式：自定义 `CustomRecipe` / `SpecialCraftingRecipe`（1.21.1 先查反编译源码确认基类、`matches(CraftingInput, Level)` 与 `assemble(CraftingInput, HolderLookup.Provider)` 的签名、`RecipeSerializer` 注册方式），不要凭记忆写
- [ ] 配方书（recipe book）里能正确显示；不需要「一键填充」也行，但不得让配方书崩溃

## 备注

- 扩容配方（`chestCount + 1`）留给 08，因为它是 GUI 的验收面
- 形态转换（泥土 ↔ 草地 ↔ 耕地）留给 09
- 需要 `03b` 的数据搬运能力才能把 `chestCount` 写进产物物品；若 03b 未完成，本票只能产出 `chestCount = 0` 的方块，请在开工前确认依赖顺序（建议 03b 先完成）
- 单元测试：`CustomRecipe` 的 `matches` 逻辑依赖 `CraftingInput`（MC 类，可能触发 bootstrap），能测就测；不能测就在报告里说明并用「代码审查 + 数据包结构校验」代替
