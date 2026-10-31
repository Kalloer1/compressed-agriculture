# 03b: 压缩方块的物品形态与合成

**What to build:** 压缩方块作为物品能带走自己的全部状态（货仓内容 + `chestCount`），放下时原样恢复；玩家能用 9 个泥土做出 1 级压缩泥土，把 9 个低级方块合成 1 个高级方块时容量档位相加、内容清空。

**Blocked by:** 03（方块与货仓）

**Status:** ready-for-agent

## 验收标准

### 物品 ↔ 货仓的数据搬运

- [ ] 破坏压缩方块 → 掉落的方块物品**携带该方块的完整货仓数据**（`schemaVersion` / `chestCount` / 27 格物品与计数）
- [ ] 放下携带数据的物品 → 新建的 `CompressedBlockEntity` 恢复出完全相同的货仓内容与 `chestCount`
- [ ] 不带数据的物品放下 → 空货仓、`chestCount = 0`
- [ ] 数据在创造模式中栏物品上不存在（中栏拿到的方块物品是干净的）
- [ ] 优先复用原版机制：`DataComponents.BLOCK_ENTITY_DATA` + `BlockItem.updateCustomBlockEntityTag`（先查 1.21.1 反编译源码确认调用链；若原版路径不适用，再自定义 `DataComponentType` 并在票里写明原因）
- [ ] 数据格式与 BE 的 `saveAdditional` / `loadAdditional` **共用同一套编码**，不允许两份独立的序列化代码（与 ADR-0002 的「共用搬运代码」同一原则）

### 合成

- [ ] 9 泥土 → 1 级压缩泥土（普通 JSON 配方，`data/compressedagriculture/recipe/`）
- [ ] 2–9 级：9 个同形态、同等级 `n-1` 的压缩方块 → 1 个等级 `n` 的同形态方块
  - `chestCount` 用 `Math.addExact` 累加 9 个输入的值
  - 结果 > `ChestCapacity.MAX_CHEST_COUNT` 时**配方不匹配**（不消耗材料、不产出）
  - 匹配成功时**内容物清空**（只保留累加后的 `chestCount`）
  - 三种形态各自独立成链，不跨形态合成
- [ ] 配方实现方式：自定义 `CustomRecipe` / `SpecialCraftingRecipe`（1.21.1 先查反编译源码确认基类与序列化器签名），并在 `RecipeSerializer` 注册
- [ ] 输入顺序无关：9 个格子里任意摆放都能匹配
- [ ] 少放一个（8 个）不匹配

## 备注

- 扩容配方（`chestCount + 1`）不在这张票，留给 08（GUI 验收面）
- 形态转换（泥土 ↔ 草地 ↔ 耕地）留给 09，本票的合成链只在同形态内升级
- 若 1.21.1 的 `CustomRecipe` 需要 `CraftingInput` 之外的东西（例如 `HolderLookup.Provider`），按源码写，不要凭记忆
- 本票完成后，`08-gui-capacity-retry.md` 才真正有 `chestCount` 可展示
