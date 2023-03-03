package com.kneelawk.graphlib.api.v1.node;

import com.kneelawk.graphlib.api.v1.GraphLib;
import com.kneelawk.graphlib.api.v1.graph.BlockNodeHolder;
import com.kneelawk.graphlib.api.v1.graph.NodeView;
import com.kneelawk.graphlib.api.v1.util.graph.Node;
import com.kneelawk.graphlib.api.v1.wire.FullWireBlockNode;
import com.kneelawk.graphlib.api.v1.wire.WireConnectionDiscoverers;
import com.kneelawk.graphlib.api.v1.wire.FullWireConnectionFilter;
import com.kneelawk.graphlib.api.v1.wire.SidedWireBlockNode;
import com.kneelawk.graphlib.api.v1.wire.SidedWireConnectionFilter;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Interface that all block nodes should implement.
 * <p>
 * A block node is a piece of immutable data that sits in a block graph and can be used to allow utilities to determine
 * which blocks are connected to which and how.
 * <p>
 * In Wired Redstone, each wire or gate has at least one block node associated with it. For example, each red-alloy wire
 * part has a single associated red-alloy wire node. These nodes are then connected according to a set of rules and then
 * traversed when ever a wire receives a redstone signal, allowing all connected wires to receive the same signal,
 * causing them all to update at the same time with the same redstone value.
 *
 * @see WireConnectionDiscoverers
 */
public interface BlockNode {
    /**
     * Gets this block node's type ID, associated with its decoder.
     * <p>
     * A block node's {@link BlockNodeDecoder} must always be registered with
     * {@link GraphLib#BLOCK_NODE_DECODER} under the same ID as returned here.
     *
     * @return the id of this block node.
     */
    @NotNull Identifier getTypeId();

    /**
     * Encodes this block node's data to an NBT element.
     * <p>
     * This can return null if this block node's type is all the data that needs to be stored.
     *
     * @return a (possibly null) NBT element describing this block node's data.
     */
    @Nullable NbtElement toTag();

    /**
     * Collects nodes in the world that this node can connect to.
     * <p>
     * <b>Contract:</b> This method must only return nodes that
     * {@link #canConnect(ServerWorld, NodeView, BlockPos, Node, Node)} would have returned <code>true</code> for.
     *
     * @param world    the world of blocks.
     * @param nodeView the world of nodes.
     * @param pos      this node's block-position.
     * @return all nodes this node can connect to.
     * @see WireConnectionDiscoverers#wireFindConnections(SidedWireBlockNode, ServerWorld, NodeView, BlockPos, Node, SidedWireConnectionFilter)
     * @see WireConnectionDiscoverers#fullBlockFindConnections(FullWireBlockNode, ServerWorld, NodeView, BlockPos, Node, FullWireConnectionFilter)
     */
    @NotNull Collection<Node<BlockNodeHolder>> findConnections(@NotNull ServerWorld world, @NotNull NodeView nodeView,
                                                               @NotNull BlockPos pos,
                                                               @NotNull Node<BlockNodeHolder> self);

    /**
     * Determines whether this node can connect to another node.
     * <p>
     * <b>Contract:</b> This method must only return <code>true</code> for nodes that would be returned from
     * {@link #findConnections(ServerWorld, NodeView, BlockPos, Node)}.
     *
     * @param world    the world of blocks.
     * @param nodeView the world of nodes.
     * @param pos      this node's block-position.
     * @param other    the other node to attempt to connect to.
     * @return whether this node can connect to the other node.
     * @see WireConnectionDiscoverers#wireCanConnect(SidedWireBlockNode, ServerWorld, BlockPos, Node, Node, SidedWireConnectionFilter)
     * @see WireConnectionDiscoverers#fullBlockCanConnect(FullWireBlockNode, ServerWorld, BlockPos, Node, Node, FullWireConnectionFilter)
     */
    boolean canConnect(@NotNull ServerWorld world, @NotNull NodeView nodeView, @NotNull BlockPos pos,
                       @NotNull Node<BlockNodeHolder> self, @NotNull Node<BlockNodeHolder> other);

    /**
     * Called when the block graph controller has determined that this specific node's connections have been changed.
     * <p>
     * This usually performs visual updates on the block associated with this node, but this can be used for other
     * things as well. This method is also called on nodes that have been removed from a graph, after the graph has
     * finished removing them.
     * <p>
     * Note: This is not called for every node change in a graph, only when this specific node's connection's have
     * changed.
     *
     * @param world the block world that this node is associated with.
     * @param nodeView the world of nodes.
     * @param pos   the block position of this node.
     * @param self this block node's holder providing information about this node's connections and graph id.
     */
    void onConnectionsChanged(@NotNull ServerWorld world, @NotNull NodeView nodeView, @NotNull BlockPos pos, @NotNull Node<BlockNodeHolder> self);

    /**
     * Block nodes are compared based on their hash-code and equals functions.
     * <p>
     * Block nodes must always implement consistent hash-code and equals functions, as this allows the block graph
     * controller to be able to correctly evaluate if nodes need to be removed or added at a given position.
     *
     * @return the hash-code of this block node's data.
     */
    @Override
    int hashCode();

    /**
     * Block nodes are compared based on their hash-code and equals functions.
     * <p>
     * Block nodes must always implement consistent hash-code and equals functions, as this allows the block graph
     * controller to be able to correctly evaluate if nodes need to be removed or added at a given position.
     *
     * @param o the other node to compare this node to.
     * @return <code>true</code> if these two nodes hold the same data, <code>false</code> otherwise.
     */
    @Override
    boolean equals(@Nullable Object o);
}
