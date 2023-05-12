package com.kneelawk.graphlib.api.graph;

import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;

import com.kneelawk.graphlib.api.node.BlockNode;
import com.kneelawk.graphlib.api.node.SidedBlockNode;
import com.kneelawk.graphlib.api.util.SidedPos;
import com.kneelawk.graphlib.api.util.graph.Node;

/**
 * Holds and manages a set of block nodes.
 */
public interface BlockGraph {
    /**
     * Gets this graph's graph ID.
     *
     * @return the ID of this graph.
     */
    long getId();

    /**
     * Gets all the nodes in this graph in the given block-position.
     *
     * @param pos the block-position to get the nodes in.
     * @return a stream of all the nodes in this graph in the given block-position.
     */
    @NotNull Stream<NodeHolder<BlockNode>> getNodesAt(@NotNull BlockPos pos);

    /**
     * Gets all the nodes in this graph in the given sided block-position.
     *
     * @param pos the sided block-position to get the nodes in.
     * @return a stream of all the nodes in this graph in the given sided block-position.
     */
    @NotNull Stream<NodeHolder<SidedBlockNode>> getNodesAt(@NotNull SidedPos pos);

    /**
     * Gets all the nodes in this graph.
     *
     * @return a stream of all the nodes in this graph.
     */
    @NotNull Stream<NodeHolder<BlockNode>> getNodes();

    /**
     * Gets all nodes in this graph with the given type.
     *
     * @param type the type that all returned nodes must be.
     * @return a stream of all the nodes in this graph with the given type.
     */
    @NotNull <T extends BlockNode> Stream<NodeHolder<T>> getNodesOfType(@NotNull Class<T> type);

    /**
     * Gets all the chunk sections that this graph currently has nodes in.
     *
     * @return a stream of all the chunk sections this graph is in.
     */
    @NotNull Stream<ChunkSectionPos> getChunks();

    /**
     * Gets the number of nodes in this graph.
     *
     * @return the number of nodes in this graph.
     */
    int size();

    /**
     * Gets whether this graph is empty.
     *
     * @return <code>true</code> if this graph has no nodes, <code>false</code> otherwise.
     */
    boolean isEmpty();
}
