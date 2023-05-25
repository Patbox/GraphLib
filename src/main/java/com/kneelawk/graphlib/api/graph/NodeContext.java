package com.kneelawk.graphlib.api.graph;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import com.kneelawk.graphlib.api.node.BlockNode;

/**
 * Context passed to a node in method calls.
 *
 * @param self       this node's holder.
 * @param blockWorld the world of blocks.
 * @param graphWorld the world of graphs.
 */
public record NodeContext(@NotNull NodeHolder<BlockNode> self, @NotNull ServerWorld blockWorld,
                          @NotNull GraphView graphWorld) {
    /**
     * This constructor is considered internal to GraphLib and not API.
     * <p>
     * This constructor is internal so that more fields can be added to node-context without breaking API.
     *
     * @param self       the node's holder.
     * @param blockWorld the world of blocks.
     * @param graphWorld the world of graphs.
     */
    @ApiStatus.Internal
    public NodeContext {
    }

    /**
     * Gets the position of this node context.
     *
     * @return the position of this node context.
     */
    public BlockPos getPos() {
        return self.getPos();
    }
}
