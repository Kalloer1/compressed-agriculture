package com.kalloer.compressedagriculture.storage;

import com.kalloer.compressedagriculture.settle.ChestCapacity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * 把 {@link ChestStorage} 暴露成 NeoForge 的 {@link IItemHandler}，让漏斗 / 管道能抽取与插入（ticket 11）。
 *
 * <p><b>long → int 的换算规则：一次操作只搬「一份普通堆叠」。</b>货仓单格上限是
 * {@code 64 × (1 + chestCount)}，到顶 {@link ChestCapacity#MAX_CHEST_COUNT} 时是 {@code 64_000_000_064}，远超
 * {@code int}；{@link ItemStack} 的数量和 {@link IItemHandler} 的返回值都是 {@code int}，直接把 long 强转成 int
 * 会溢出成负数或垃圾值。所以：
 * <ul>
 *   <li>{@link #getStackInSlot} / {@link #extractItem} 每次最多吐 {@link #transferLimit(ItemStack)} 个，取
 *       {@code min(物品自身堆叠上限, 64)}——永远不会超出一个 {@code ItemStack} 装得下的量；</li>
 *   <li>{@link #insertItem} 同理每次搬入不超过一批，但会<b>循环</b>搬到自己搬不动为止；剩下的才返回给调用方。</li>
 * </ul>
 * 漏斗本来就每次只搬一格，这个限制不损失功能性，换来的是「不存在任何一次绕进 int 的 long 值」。
 *
 * <p><b>存储上限永远是 {@link ChestStorage#capacityPerSlot()}（{@code 64 × (1 + chestCount)}），不是 64。</b>
 * 64 只是「一次搬多少」的搬运单位；{@link #isItemValid} 也只回答「这格能不能放这个物品」
 * （见 {@link IItemHandler#isItemValid}：它的约定是<b>不考虑</b>容器当前内容与是否已满），所以装了 64 个以上的槽
 * 对同种物品仍然有效——否则漏斗会把「容量随扩容档位上涨」这个核心玩法卡在 64。
 *
 * <p><b>写入走三阶段契约。</b>{@link #insertItem} 不直接改内部状态，而是
 * {@link CompressedBlockEntity#tryReserve} → {@link CompressedBlockEntity#commit()}（失败则
 * {@link CompressedBlockEntity#rollback()}），与 {@code settle} 是同一条路径；{@code commit()} 里带
 * {@code setChanged()}，所以方块能被正确标脏存档。
 *
 * <p>只在服务端注册（见 {@code CompressedAgriculture}）：货仓内容不在客户端被自动化设备读写，
 * 客户端拿到的仍是区块包里的那份快照。
 */
public class CompressedItemHandler implements IItemHandler {
    /**
     * 一次操作的搬运上限。取 64 而不是「物品自身堆叠上限」的放大版：漏斗 / 管道一次只搬一格，
     * 与 {@link ItemHandlerHelper} 的默认行为一致。
     */
    private static final int MAX_TRANSFER = 64;

    private final CompressedBlockEntity chest;

    public CompressedItemHandler(CompressedBlockEntity chest) {
        this.chest = chest;
    }

    @Override
    public int getSlots() {
        return ChestStorage.SLOT_COUNT;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (!isValidSlot(slot)) {
            return ItemStack.EMPTY;
        }
        ChestStorage storage = this.chest.storage();
        ResourceLocation itemId = storage.itemInSlot(slot);
        long stored = storage.normalizedCountInSlot(slot);
        if (itemId == null || stored <= 0L) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(itemId));
        if (stack.isEmpty()) {
            // 存档里的物品 id 已不存在（模组被移除）：当空槽处理，不吐空气
            return ItemStack.EMPTY;
        }
        stack.setCount((int) Math.min(stored, transferLimit(stack)));
        return stack;
    }

    /**
     * 插入物品，返回<b>没放进去</b>的剩余量。
     *
     * <p><b>不限量、循环搬。</b>每次预留最多 {@link #transferLimit} 个（这是搬运单位，不是存储上限），
     * 再预留→提交，直到上游的 {@code stack} 搬完、或者这格已经到 {@link ChestStorage#capacityPerSlot()}
     * （甚至被别的物品占了）。不循环的话，调用方一次传 1728 个（一潜影盒）就永远只能进来 64 个。
     *
     * <p>终止条件双保险：单轮预留量至少 1 个时才会继续（{@link #transferLimit} 恒 ≥ 1）；而每次循环都会让
     * 这格的已存量等量增长，在 {@code chestCount == MAX_CHEST_COUNT} 的极端档位下仍然会在有限步内走到格满。
     *
     * <p>只读调用（{@code simulate = true}）走第二条路：每一轮预留完立即 {@code rollback}，所以状态与调用前完全一样，
     * 只有返回的剩余量反映「能不能装下」。不用 {@link ItemHandlerHelper#insertItemStacked} 是因为它不接受槽位参数。
     *
     * <p><b>落格不保证是 {@code slot}：</b>夹紧用的是 {@code slot} 的空位，但真正写入走
     * {@link ChestStorage#tryReserve}，它从 0 号格开始扫、优先补已有的同物品堆。总量与返回值是准的，
     * 但回读 {@link #getStackInSlot} 时可能不在入参那一格。要严格按槽写请用 {@link ChestStorage#insert}。
     */
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (!isValidSlot(slot) || stack.isEmpty()) {
            return stack;
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        int perTransfer = transferLimit(stack);
        int remaining = stack.getCount();
        // 先过一道「这格收不收这个物品」，也遮住「格已满」：否则每轮都白跑一次 tryReserve
        if (!isItemValid(slot, stack)) {
            return stack;
        }

        ChestStorage storage = this.chest.storage();
        while (remaining > 0) {
            // 请求量必须先按本格空位夹一次：tryReserve 是原子操作（要么整条预留成功、要么一个都不留），
            // 直接请求 64 而只剩 10 个空位时会整条失败，表现就是「有 10 个空位却一个都塞不进去」。
            long slotFree = Math.max(0L, storage.capacityPerSlot() - storage.normalizedCountInSlot(slot));
            int request = (int) Math.min(Math.min(remaining, perTransfer), slotFree);
            if (request <= 0) {
                break;
            }
            if (!this.chest.tryReserve(itemId, request)) {
                break;
            }
            if (simulate) {
                this.chest.rollback();
            } else {
                this.chest.commit();
            }
            remaining -= request;
            if (remaining > 0 && !isItemValid(slot, stack)) {
                // 本格刚好装满（或因其他原因不再接受）：没必要再试
                break;
            }
        }

        if (remaining == stack.getCount()) {
            // 一个都没搬进去：按 IItemHandler 约定原样返回入参
            return stack;
        }
        return copyWithCount(stack, remaining);
    }

    /**
     * 取出一批物品，返回实际取出的堆叠（数量恒为 {@code int} 范围内的普通堆叠）。
     *
     * <p>扣减走 {@link ChestStorage#extract}（ticket 08 的界面取出是同一条路径），然后再走一次
     * {@link CompressedBlockEntity#changedAndSync()}：不标脏的话区块卸载时取出的东西会「复活」，
     * 不同步的话打开着的界面会停在旧计数。
     */
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (!isValidSlot(slot) || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ChestStorage storage = this.chest.storage();
        ResourceLocation itemId = storage.itemInSlot(slot);
        if (itemId == null || storage.normalizedCountInSlot(slot) <= 0L) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(itemId));
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int want = Math.min(amount, transferLimit(stack));
        long taken = simulate ? Math.min(want, storage.normalizedCountInSlot(slot)) : storage.extract(slot, want);
        if (taken <= 0L) {
            return ItemStack.EMPTY;
        }
        if (!simulate) {
            this.chest.changedAndSync();
        }
        stack.setCount((int) taken);
        return stack;
    }

    /**
     * 单槽的<b>搬运单位</b>上限，恒为 {@link #MAX_TRANSFER}（64）——<b>不是存储上限</b>。
     *
     * <p>存储上限是 {@link ChestStorage#capacityPerSlot()}（{@code 64 × (1 + chestCount)}），它远大于一个普通堆叠，
     * 也被 {@code chestCount == MAX_CHEST_COUNT} 推到 {@code long} 才装得下的量，{@code int} 返回值放不下。
     *
     * <p>为什么恒为 64：
     * <ul>
     *   <li>原版漏斗不看这个值——{@code HopperBlockEntity.tryMoveInItem} 只按 {@code ItemStack.getMaxStackSize()} 合并，
     *       而 {@code HopperBlockEntity.addItem} 对本模组的 BlockEntity 走的是 {@code Capabilities.ItemHandler.BLOCK}
     *       这条 neo 注入路径（即直接调 {@link #insertItem}），所以容量不受它限制；</li>
     *   <li>但这个值必须能代表「一次搬运」：返回容量会让 {@link ItemHandlerHelper#calcRedstoneFromInventory} 这类
     *       拿 {@code getSlotLimit} 当分母的算法失衡，而返回 64 则与 {@code getStackInSlot} 每次吐一份堆叠一致。</li>
     * </ul>
     *
     * <p>不在这里算「剩余容量」：「现在还能再放多少」由 {@link #isItemValid}（当前状态）回答，
     * 而不是混合两者得到一个随内容物变动的上限；真要算剩余量请用 {@code simulate = true} 的 {@link #insertItem}。
     */
    @Override
    public int getSlotLimit(int slot) {
        return isValidSlot(slot) ? MAX_TRANSFER : 0;
    }

    /**
     * 该槽能不能放这个物品——<b>只回答能不能放，不用 64 当上限，也不用容量当上限</b>。
     *
     * <p>这个是 {@link IItemHandler#isItemValid} 的约定：回答「能不能」，不回答「现在放不放得下」（那些靠 simulate 插入）。
     * 漏斗的上游会把「这里永远放不进去」的物品跳过（不堵后面的槽），所以：
     * <ul>
     *   <li>空格（含「物品为 null 但计数残留」的归一化情形）：任何物品都能放；</li>
     *   <li>该物品（包括带相同数据组件的一类物品）：只要该格还没到 {@link ChestStorage#capacityPerSlot()} 就能放；</li>
     *   <li>其他物品：不能放——货仓一格只承载一种物品。</li>
     * </ul>
     *
     * <p>比容量：原实现拿 {@link #MAX_TRANSFER}（64）当已存上限，于是装了 64 个以上的槽对同种物品报 false，
     * 漏斗就再也推不进来——正好把「容量随扩容档位上涨」这个核心玩法卡在 64，而 {@link #insertItem} 其实还能收。
     * 已存量必须用 {@link ChestStorage#normalizedCountInSlot}：空格可能是 {@code slotItems == null} 而计数残留的状态，
     * 直接读 {@code slotCounts} 会得到无关的数字。
     *
     * <p>分类用 {@link ItemStack#is}＋components 比较，与本类写入路径一致（货仓只按物品 ID 记身份）。
     *
     * <p><b>别把本方法与界面槽的 {@code CompressedChestMenu.StorageSlot#mayPlace} 「统一」掉：</b>那个回答的是
     * 「这类物品能不能进这种槽」（原版 {@code Slot} 语义，不看空位），本方法回答的是「这一格<b>现在</b>还能不能塞」
     * （必须看容量：否则原版漏斗的 {@code isItemValid} 预检会把扩容后的槽当满格拒掉，就是上面 P1 那条）。
     */
    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (!isValidSlot(slot) || stack.isEmpty() || !isStorable(stack)) {
            return false;
        }
        ChestStorage storage = this.chest.storage();
        ResourceLocation itemId = storage.itemInSlot(slot);
        long stored = storage.normalizedCountInSlot(slot);
        if (itemId == null || stored <= 0L) {
            // 空格（含「物品为 null 但计数残留」的归一化情形）：任何物品都能进
            return true;
        }
        return itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                && stored < storage.capacityPerSlot();
    }

    /**
     * 一次搬运的上限 = {@code min(物品自身堆叠上限, 64)}。
     *
     * <p>用物品自己的堆叠上限而不是恒定 64：珍珠、蛋糕这类上限是 16 / 1 的物品，吐 64 个会造出
     * 一个超过上限的 {@code ItemStack}。
     */
    private static int transferLimit(ItemStack stack) {
        return Math.max(1, Math.min(stack.getMaxStackSize(), MAX_TRANSFER));
    }

    private static boolean isValidSlot(int slot) {
        return slot >= 0 && slot < ChestStorage.SLOT_COUNT;
    }

    /**
     * 这个物品能不能进货仓：货仓一格只记「物品 ID + {@code long} 计数」（ADR-0017）。
     *
     * <p>带任何数据组件（{@link ItemStack#getComponentsPatch()} 非空）的物品都不收，因为它们进去就会被降级成裸物品：
     * <ul>
     *   <li>附魔书 → 普通书，附魔剑 → 普通剑；
     *   <li>损坏的钻石镐（{@code DAMAGE}）→ 取出变满耐久（免费修复）；
     *   <li>潜影盒（{@code CONTAINER}）→ <b>内容物直接销毁</b>；
     *   <li>药水 → 水瓶，命名物品 → 丢名字。
     * </ul>
     *
     * <p>三道门都用它：界面槽的 {@code StorageSlot#mayPlace}、界面的 Shift 放入、以及本类的
     * {@link #isItemValid}（漏斗 / 管道）。{@code settle} 那条路不经过这里——作物掉落只有物品 ID，没有组件。
     */
    public static boolean isStorable(ItemStack stack) {
        return !stack.isEmpty() && stack.getComponentsPatch().isEmpty();
    }

    // 不改调用方传进来的那个 stack：IItemHandler 的约定是「返回剩余量」，入参在调用后仍归调用方所有
    private static ItemStack copyWithCount(ItemStack stack, int count) {
        if (count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack remainder = stack.copyWithCount(count);
        return remainder;
    }
}
