package com.kneelawk.graphlib.api.wire;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.util.math.Direction;

import com.kneelawk.graphlib.api.graph.NodeContext;
import com.kneelawk.graphlib.api.graph.user.BlockNode;
import com.kneelawk.graphlib.api.util.HalfLink;

/**
 * A block node wire that occupies a full block instead of sitting on the side of a block.
 * <p>
 * This is usually what most non-gate machines are.
 */
public interface FullWireBlockNode extends BlockNode {
    /**
     * Checks whether this block node can connect to the given other block node.
     *
     * @param ctx      the node context for this node.
     * @param onSide   the side of this block node that the other node is trying to connect to.
     * @param wireSide the side of the block that the connecting wire is at, or <code>null</code> if the wire is a full
     *                 block or otherwise non-sided.
     * @param link     the link to the block node that could possibly connect to this node.
     * @return <code>true</code> if this node and the other node should be allowed to connect, <code>false</code>
     * otherwise.
     */
    default boolean canConnect(@NotNull NodeContext ctx, @NotNull Direction onSide, @Nullable Direction wireSide,
                               @NotNull HalfLink link) {
        return true;
    }
}
