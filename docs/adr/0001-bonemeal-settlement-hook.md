# 0001 · 骨粉路径改用「统一入口后置挂钩」而非后置事件

- 状态：已采纳（2026-10-31）
- 影响面：普通作物成熟结算的全部触发逻辑

## 背景

方案 v1.9.2 §5.4 把普通作物的成熟结算拆成两条触发路径，其中骨粉路径写作：

> `BonemealEvent` 本身可取消……**不取消 `BonemealEvent`**，只让原版正常执行 `performBonemeal`。在事件返回之后，**同一监听器中立即检查**……

并在伪代码注释里留下一句未决：

> 原版 `performBonemeal` 在事件返回后执行；此处依赖事件线程模型，
> 若事件在 performBonemeal **之前**触发，则在监听器末尾使用
> serverLevel.getBlockState(pos) 读取催熟后的状态。

## 问题

「事件返回之后」这句话在本平台上不成立。

`BonemealEvent` 在 NeoForge 21.1.235 中是 `ICancellableEvent`，由 `EventHooks.fireBonemealEvent` 投递。它**只有一个**触发点，位置在 `BoneMealItem.applyBonemeal` 的开头、`performBonemeal` 调用**之前**。监听器执行完毕后，控制流返回 `applyBonemeal` 继续往下走，此时才调用 `performBonemeal`。监听器没有任何「在它之后」可执行的位置。

同时 `BonemealEvent` **没有** `Post` 变体（21.1.235 的 sources jar 中只有 `net/neoforged/neoforge/event/entity/player/BonemealEvent.java` 一个文件），因此「等它结束再看」在后置事件层面也无处可挂。

结论：文档伪代码里那句 `serverLevel.getBlockState(pos)` 读到的是**催熟前**的状态，`isMatureCrop(after)` 恒为 false（骨粉本身不改变事件携带的 state）。骨粉路径会静默失效——而这恰好是文档 §12 列出的最高风险项。

另一个细节：`setSuccessful(boolean)` 内部会调用 `setCanceled(true)`。文档写的「不取消事件、只让原版执行」与「用 setSuccessful 控制语义」互斥，二者只能取其一。

## 决策

骨粉路径**不使用** `BonemealEvent` 做前后状态对比。改用：

挂 `BonemealEvent` 监听器，仅记录 `(Dimension, BlockPos)` 到本 tick 的待观察集合，**不取消、不改 `setSuccessful`**，让原版完整执行 `performBonemeal`。随后在 `ServerTickEvent.Post`（每 tick 末尾）清空该集合，对每个位置读当前 `BlockState`：

- 已成熟 → 调统一入口结算；
- 未成熟 → 什么都不做（对应文档「路径 C」）；
- 位置已不是作物 → 什么都不做。

随机刻路径 `CropGrowEvent.Post` 保持原样，它本来就在生长成功之后触发，状态已经更新。

两条路径汇入同一个统一入口，靠每 tick 的待观察集合天然去重——同一 tick 内同位置多次骨粉只结算一次。

## 备选方案

1. **用 `BonemealEvent.Post`**：不存在，放弃。
2. **在监听器里自己调 `performBonemeal` 然后取消事件**：可行，但要复刻 `isBonemealSuccess` / `isValidBonemealTarget` 的调用顺序，且会与 `setSuccessful` 的语义打架，容易在其他模组骨粉作物上产生分歧。放弃。
3. **`playerDestroy` 式挂钩**：不适用于「作物自己长熟」这种无玩家参与的场景。放弃。

## 后果

- 结算延迟最多 1 tick（同 tick 内事件按顺序执行，`ServerTickEvent.Post` 在末尾）。玩家不可感知。
- 待观察集合必须按维度分区存储，且每 tick 无条件清空，否则区块卸载后会泄漏。
- `CropGrowEvent.Post` 与骨粉路径共用统一入口，两条路径的去重逻辑收敛到「同一 tick 同一位置只算一次」，不再需要文档 §5.4 描述的独立「已结算集合」。

## 实现要点（已验证）

- `ServerTickEvent.Post` 在 NeoForge 21.1.235 存在，为 `net.neoforged.neoforge.event.tick.ServerTickEvent.Post`，每服务端 tick 触发一次，只服务端。
- 它只携带 `MinecraftServer`（`getServer()`），**不携带 Level**。待观察集合按维度分区，清空时用 `server.getAllLevels()` 遍历，逐个 `getBlockState(pos)`。
- 待观察集合的键用 `ResourceKey<Level>` + `BlockPos.asLong()`，不要用 `ServerLevel` 引用做键（区块卸载后 Level 对象仍存活，但引用会阻止预期回收）。
- `BonemealEvent` 在客户端与服务端**都会**触发（类注释原文："This event is fired on both client and server"）。监听器开头必须 `if (!(event.getLevel() instanceof ServerLevel)) return;`，否则客户端玩家每次骨粉都会往集合里塞一个永远不会被清空处理的位置。
