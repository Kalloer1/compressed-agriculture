package com.kalloer.compressedagriculture.tree;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import com.kalloer.compressedagriculture.registry.ModTags;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 从树苗位置枚举一棵树的全部方块，分成原木 / 树叶 / 挂件三类（ADR-0011，算法照抄 Create 的 {@code TreeCutter}）。
 *
 * <p>这是纯只读逻辑：不写世界、不结算、不拆除。{@code 05b} 的事件接线负责拿这个结果去取掉落和拆方块。
 *
 * <p>判据全部是标签或行为类判断，不出现任何具体树木 ID：
 * <ul>
 *   <li>原木：{@code #minecraft:logs} / {@link ModTags.Blocks#SLIMY_LOGS} / {@code minecraft:mushroom_stem}</li>
 *   <li>树根：{@link ModTags.Blocks#TREE_ROOTS}，命中后允许向 DOWN 扩散</li>
 *   <li>树叶：任何带 {@code distance} 整数属性、且不是 {@code STABILITY_DISTANCE} 的方块；蘑菇块按固定水平半径收</li>
 *   <li>挂件：{@link ModTags.Blocks#TREE_ATTACHMENTS}</li>
 * </ul>
 *
 * <p>结果集合用 {@link LinkedHashSet}，顺序由固定的 BFS 起点与固定的邻居扫描顺序决定，可复现。
 */
public final class TreeEnumeration {
    /** 从树苗位置向上找树干的最远格数。巨型丛林树的树干从树苗位置起算，32 格足够覆盖原版全部树。 */
    private static final int MAX_TRUNK_SEARCH = 32;

    private TreeEnumeration() {}

    /** 一次枚举的结果。三个集合互不相交，顺序可复现。 */
    public record Result(Set<BlockPos> logs, Set<BlockPos> leaves, Set<BlockPos> attachments) {
        public Result {
            logs = Collections.unmodifiableSet(logs);
            leaves = Collections.unmodifiableSet(leaves);
            attachments = Collections.unmodifiableSet(attachments);
        }

        public static Result empty() {
            return new Result(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
        }

        public boolean isEmpty() {
            return logs.isEmpty() && leaves.isEmpty() && attachments.isEmpty();
        }
    }

    /**
     * 从树苗位置枚举整棵树。
     *
     * @param level     服务端世界
     * @param saplingPos 树苗所在位置；树长出来后原版已把这一格换成了树干的最低一格
     * @return 三分类结果；树苗位置上方找不到树干时返回空结果
     */
    public static Result enumerate(ServerLevel level, BlockPos saplingPos) {
        BlockPos trunk = findTrunk(level, saplingPos);
        if (trunk == null) {
            return Result.empty();
        }
        return enumerateFromTrunk(level, trunk);
    }

    /**
     * 判断「砍掉 {@code cutPos} 这一格」是不是砍掉整棵树的最低一根原木。
     *
     * <p>判据：{@code cutPos} 下方（连通的原木链里、Y 更低的位置）若还有原木，说明砍的是树的中段，返回 {@code false}；
     * 否则返回 {@code true}。精确推导见 ticket 05 的 Further Notes。
     */
    public static boolean validateCut(ServerLevel level, BlockPos cutPos) {
        return !hasLogBelow(level, cutPos);
    }

    /** 从树苗位置向上找树干的最低一格；树苗位置本身就是原木时直接用它。 */
    private static BlockPos findTrunk(ServerLevel level, BlockPos saplingPos) {
        if (isLog(stateAt(level, saplingPos))) {
            return saplingPos;
        }
        BlockPos.MutableBlockPos cursor = saplingPos.mutable();
        for (int i = 1; i <= MAX_TRUNK_SEARCH; i++) {
            cursor.move(Direction.UP);
            if (!level.isLoaded(cursor)) {
                return null;
            }
            if (isLog(stateAt(level, cursor))) {
                return cursor.immutable();
            }
        }
        return null;
    }

    private static Result enumerateFromTrunk(ServerLevel level, BlockPos trunk) {
        Set<BlockPos> logs = new LinkedHashSet<>();
        Set<BlockPos> leaves = new LinkedHashSet<>();
        Set<BlockPos> attachments = new LinkedHashSet<>();
        Set<BlockPos> visited = new LinkedHashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();

        // 第一步：树干。从树干周围 3×3×2 起步，只沿原木 / 树根扩散，每次只向 UP 扩（横向与向下由起步的 3×3 覆盖）。
        // 树干自己必须先记进 logs：Create 的起点是已经被砍掉的那一格（空气），我们的起点是还立着的树干最低一格。
        logs.add(trunk);
        visited.add(trunk);
        forNeighbours(trunk, 0, 1, visited, frontier::add);
        boolean hasRoots = false;
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            if (!visited.add(current)) {
                continue;
            }
            BlockState state = stateAt(level, current);
            if (isRoot(state)) {
                hasRoots = true;
            } else if (!isLog(state)) {
                continue;
            }
            logs.add(current);
            forNeighbours(current, 0, 1, visited, frontier::add);
        }

        // 第二步：有树根时向 DOWN 扩，把地下的根也算进 logs（红树）。
        if (hasRoots) {
            Set<BlockPos> trunkLogs = new LinkedHashSet<>(logs);
            visited.clear();
            visited.addAll(logs);
            frontier.addAll(logs);
            while (!frontier.isEmpty()) {
                BlockPos current = frontier.poll();
                if (!isRoot(stateAt(level, current))) {
                    continue;
                }
                if (!trunkLogs.contains(current)) {
                    logs.add(current);
                }
                forNeighbours(current, -1, 0, visited, neighbour -> {
                    if (visited.add(neighbour)) {
                        frontier.add(neighbour);
                    }
                });
            }
        }

        // 第三步：从所有原木出发找树叶与挂件。树叶沿 distance 递增方向扩散，蘑菇块按固定水平半径收。
        visited.clear();
        visited.addAll(logs);
        frontier.clear();
        frontier.addAll(logs);
        while (!frontier.isEmpty()) {
            BlockPos previous = frontier.poll();
            BlockState previousState = stateAt(level, previous);
            int previousLeafDistance = isLeaf(previousState) ? leafDistance(previousState) : 0;

            forNeighbours(previous, -1, 1, visited, current -> {
                BlockState state = stateAt(level, current);
                if (state.is(ModTags.Blocks.TREE_ATTACHMENTS)) {
                    attachments.add(current);
                    visited.add(current);
                    return;
                }
                int horizontalDistance = Math.max(
                        Math.abs(current.getX() - trunk.getX()),
                        Math.abs(current.getZ() - trunk.getZ()));
                if (horizontalDistance <= nonDecayingLeafDistance(state) && visited.add(current)) {
                    leaves.add(current);
                    frontier.add(current);
                    return;
                }
                if (isLeaf(state) && leafDistance(state) > previousLeafDistance && visited.add(current)) {
                    leaves.add(current);
                    frontier.add(current);
                }
            });
        }

        return new Result(logs, leaves, attachments);
    }

    /** {@code cutPos} 的连通原木链里是否还有 Y 更低的原木。树根不算（红树的根在树干下方，不影响「这是最低一根原木」）。 */
    private static boolean hasLogBelow(ServerLevel level, BlockPos cutPos) {
        Set<BlockPos> visited = new LinkedHashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        visited.add(cutPos);
        frontier.add(cutPos);
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = current.relative(direction);
                if (!visited.add(neighbour)) {
                    continue;
                }
                if (!isLog(stateAt(level, neighbour))) {
                    continue;
                }
                if (neighbour.getY() < cutPos.getY()) {
                    return true;
                }
                frontier.add(neighbour);
            }
        }
        return false;
    }

    /**
     * 把 {@code center} 的 3×3×(maxY-minY+1) 盒子喂给 {@code acceptor}，跳过已访问的位置。
     *
     * <p>扫描顺序固定为 X → Y → Z，保证枚举结果可复现。
     */
    private static void forNeighbours(BlockPos center, int minY, int maxY, Set<BlockPos> visited,
            Consumer<BlockPos> acceptor) {
        for (int x = center.getX() - 1; x <= center.getX() + 1; x++) {
            for (int y = center.getY() + minY; y <= center.getY() + maxY; y++) {
                for (int z = center.getZ() - 1; z <= center.getZ() + 1; z++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (visited.contains(candidate)) {
                        continue;
                    }
                    acceptor.accept(candidate);
                }
            }
        }
    }

    /** 未加载的区块按空气处理：跳过而不是强行加载（ticket 05 Further Notes）。 */
    private static BlockState stateAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return level.getBlockState(pos);
    }

    private static boolean isLog(BlockState state) {
        return state.is(BlockTags.LOGS)
                || state.is(ModTags.Blocks.SLIMY_LOGS)
                || state.is(Blocks.MUSHROOM_STEM);
    }

    private static boolean isRoot(BlockState state) {
        return state.is(ModTags.Blocks.TREE_ROOTS);
    }

    /** 任何带 {@code distance} 整数属性、且不是 {@code STABILITY_DISTANCE} 的方块都算树叶，覆盖模组树叶。 */
    private static boolean isLeaf(BlockState state) {
        return findDistanceProperty(state) != null;
    }

    private static int leafDistance(BlockState state) {
        IntegerProperty property = findDistanceProperty(state);
        return property == null ? 0 : state.getValue(property);
    }

    private static IntegerProperty findDistanceProperty(BlockState state) {
        for (Property<?> property : state.getValues().keySet()) {
            if (property instanceof IntegerProperty integerProperty
                    && property.getName().equals("distance")
                    && property != BlockStateProperties.STABILITY_DISTANCE) {
                return integerProperty;
            }
        }
        return null;
    }

    /**
     * 蘑菇块、下界疣块、垂泪藤没有「离树干多远」的语义，按固定水平半径收（红菇 2、褐菇 3）。
     *
     * @return 固定半径；不是这类方块时返回 -1（任何水平距离都不命中）
     */
    private static int nonDecayingLeafDistance(BlockState state) {
        if (state.is(Blocks.RED_MUSHROOM_BLOCK)) {
            return 2;
        }
        if (state.is(Blocks.BROWN_MUSHROOM_BLOCK)) {
            return 3;
        }
        if (state.is(BlockTags.WART_BLOCKS) || state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT)) {
            return 3;
        }
        return -1;
    }
}
