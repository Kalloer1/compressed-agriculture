# 03b: 压缩方块的物品形态（货仓随方块搬运）

**What to build:** 压缩方块作为物品能带走自己的全部状态（货仓内容 + `chestCount`），放下时原样恢复。玩家拆掉一块装了 3 亿份小麦的压缩耕地，再放回去，东西还在。

**Blocked by:** 03（方块与货仓）

**Status:** ready-for-agent

## 验收标准

- [ ] 破坏压缩方块 → 掉落的方块物品**携带该方块的完整货仓数据**（`schemaVersion` / `chestCount` / 27 格物品与计数）
- [ ] 放下携带数据的物品 → 新建的 `CompressedBlockEntity` 恢复出完全相同的货仓内容与 `chestCount`
- [ ] 不带数据的物品放下 → 空货仓、`chestCount = 0`
- [ ] 创造模式中栏拿到的方块物品是干净的（无数据组件）
- [ ] 数据格式与 BE 的 `saveAdditional` / `loadAdditional` **共用同一套编码**，不允许两份独立的序列化代码（与 ADR-0002 的「共用搬运代码」同一原则）
- [ ] 优先复用原版机制：`DataComponents.BLOCK_ENTITY_DATA` + `BlockItem.updateCustomBlockEntityTag`。**先查 1.21.1 反编译源码确认调用链**（`BlockItem.place` 是否无条件调用、`CustomData` 的 codec 是否直接吃 `CompoundTag`）；若原版路径不适用，再自定义 `DataComponentType`，并在票里写明原因
- [ ] 掉落表改动：27 张 loot table 需要 `minecraft:copy_components`（或等价手段）把 BE 数据搬进掉落物
- [ ] `03` 已存在的 27 张 loot table 目前只掉自身，本票在其上追加，不重建

## 备注

- 不做合成（`03c`）、不做 GUI（08）、不做活塞迁移（09）
- 活塞迁移与形态转换要复用同一套 BE 搬运代码；本票只做「物品 ↔ BE」这一段，但**把搬运方法写成可复用的静态工具**（例如 `CompressedBlockEntity.copyData(BlockEntity from, BlockEntity to)` 或 `CompoundTag` 级别的 save/load），供 09 直接调用
- 游戏内验证需要客户端，headless 环境只能验证「编译 + 数据包结构 + 单元测试」；在报告里如实标注哪些没验证
