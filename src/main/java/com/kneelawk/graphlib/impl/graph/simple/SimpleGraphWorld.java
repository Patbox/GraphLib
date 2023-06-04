package com.kneelawk.graphlib.impl.graph.simple;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterable;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;

import com.kneelawk.graphlib.api.event.GraphLibEvents;
import com.kneelawk.graphlib.api.graph.BlockGraph;
import com.kneelawk.graphlib.api.graph.GraphUniverse;
import com.kneelawk.graphlib.api.graph.GraphView;
import com.kneelawk.graphlib.api.graph.GraphWorld;
import com.kneelawk.graphlib.api.graph.LinkHolder;
import com.kneelawk.graphlib.api.graph.NodeHolder;
import com.kneelawk.graphlib.api.graph.user.BlockNode;
import com.kneelawk.graphlib.api.graph.user.LinkEntity;
import com.kneelawk.graphlib.api.graph.user.LinkEntityFactory;
import com.kneelawk.graphlib.api.graph.user.LinkKey;
import com.kneelawk.graphlib.api.graph.user.NodeEntity;
import com.kneelawk.graphlib.api.graph.user.NodeEntityFactory;
import com.kneelawk.graphlib.api.graph.user.SidedBlockNode;
import com.kneelawk.graphlib.api.util.ChunkSectionUnloadTimer;
import com.kneelawk.graphlib.api.util.HalfLink;
import com.kneelawk.graphlib.api.util.LinkPos;
import com.kneelawk.graphlib.api.util.NodePos;
import com.kneelawk.graphlib.api.util.SidedPos;
import com.kneelawk.graphlib.api.world.SaveMode;
import com.kneelawk.graphlib.api.world.UnloadingRegionBasedStorage;
import com.kneelawk.graphlib.impl.Constants;
import com.kneelawk.graphlib.impl.GLLog;
import com.kneelawk.graphlib.impl.graph.GraphWorldImpl;

/**
 * Holds and manages all block graphs for a given world.
 * <p>
 * This is the default implementation and will likely be the only implementation. I decided to extract the intentional
 * API methods to an interface so that I could have more control over what methods were being called and to open up the
 * possibility of maybe eventually making a cubic-chunks implementation of GraphLib or something.
 */
public class SimpleGraphWorld implements AutoCloseable, GraphView, GraphWorld, GraphWorldImpl {
    /**
     * Graphs will unload 1 minute after their chunk unloads or their last use.
     */
    private static final int MAX_AGE = 20 * 60;
    private static final int INCREMENTAL_SAVE_FACTOR = 10;

    final SimpleGraphUniverse universe;

    final ServerWorld world;

    private final UnloadingRegionBasedStorage<SimpleBlockGraphChunk> chunks;

    private final ChunkSectionUnloadTimer timer;

    private final Path graphsDir;

    private final Path stateFile;

    private final SaveMode saveMode;

    private final Long2ObjectMap<SimpleBlockGraph> loadedGraphs = new Long2ObjectLinkedOpenHashMap<>();
    private final LongSet unsavedGraphs = new LongOpenHashSet();

    private final ObjectSet<BlockPos> nodeUpdates = new ObjectLinkedOpenHashSet<>();
    private final ObjectSet<UpdatePos> connectionUpdates = new ObjectLinkedOpenHashSet<>();
    private final ObjectSet<NodeHolder<BlockNode>> callbackUpdates = new ObjectLinkedOpenHashSet<>();

    private boolean stateDirty = false;
    private long prevGraphId = -1L;

    private boolean closed = false;

    public SimpleGraphWorld(SimpleGraphUniverse universe, @NotNull ServerWorld world, @NotNull Path path,
                            boolean syncChunkWrites) {
        this.universe = universe;
        this.chunks = new UnloadingRegionBasedStorage<>(world, path.resolve(Constants.REGION_DIRNAME), syncChunkWrites,
            (compound, pos, markDirty) -> new SimpleBlockGraphChunk(compound, pos, markDirty, universe),
            SimpleBlockGraphChunk::new, universe.saveMode);
        this.world = world;
        this.saveMode = universe.saveMode;
        graphsDir = path.resolve(Constants.GRAPHS_DIRNAME);
        stateFile = path.resolve(Constants.STATE_FILENAME);
        timer = new ChunkSectionUnloadTimer(world.getBottomSectionCoord(), world.getTopSectionCoord(), MAX_AGE);

        try {
            Files.createDirectories(graphsDir);
        } catch (IOException e) {
            throw new RuntimeException("Unable to create graphs dir: '" + graphsDir + "'. This is a fatal exception.",
                e);
        }

        loadState();
    }

    // ---- Lifecycle Methods ---- //

    @Override
    public void onWorldChunkLoad(@NotNull ChunkPos pos) {
        if (closed) {
            // Ignore chunk loads if we're closed.
            // In case something decides to try and load a chunk while saving data :/
            return;
        }

        chunks.onWorldChunkLoad(pos);
        timer.onWorldChunkLoad(pos);

        loadGraphs(pos);
    }

    @Override
    public void onWorldChunkUnload(@NotNull ChunkPos pos) {
        chunks.onWorldChunkUnload(pos);
        timer.onWorldChunkUnload(pos);
    }

    @Override
    public void tick() {
        chunks.tick();
        timer.tick();

        tickGraphs();
        handleNodeUpdates();
        handleConnectionUpdates();
        handleCallbackUpdates();

        unloadGraphs();
        saveUnsvedGraphs();
    }

    @Override
    public void saveChunk(@NotNull ChunkPos pos) {
        saveState();
        saveGraphs(pos);
        chunks.saveChunk(pos);
    }

    @Override
    public void saveAll(boolean flush) {
        // This can be useful sometimes but causes log spam in prod
//        GLLog.info("Saving block-graph for '{}'/{}", world, world.getRegistryKey().getValue());

        saveAllGraphs();
        saveState();

        chunks.saveAll();
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }

        closed = true;

        saveAllGraphs();
        saveState();

        chunks.close();
    }

    // ---- Public Interface Methods ---- //

    /**
     * Gets the universe this graph-view belongs to.
     *
     * @return the universe this belongs to.
     */
    @Override
    public @NotNull GraphUniverse getUniverse() {
        return universe;
    }

    /**
     * Gets all nodes in the given block-position.
     *
     * @param pos the block-position to get nodes in.
     * @return a stream of the nodes in the given block-position.
     */
    @Override
    public @NotNull Stream<NodeHolder<BlockNode>> getNodesAt(@NotNull BlockPos pos) {
        // no need for a .distict() here, because you should never have the same node be part of multiple graphs
        return getAllGraphIdsAt(pos).mapToObj(this::getGraph).filter(Objects::nonNull).flatMap(g -> g.getNodesAt(pos));
    }

    /**
     * Gets all nodes in the given sided block-position.
     *
     * @param pos the sided block-position to get the nodes in.
     * @return a stream of the nodes in the given sided block-position.
     */
    @Override
    public @NotNull Stream<NodeHolder<SidedBlockNode>> getNodesAt(@NotNull SidedPos pos) {
        return getAllGraphIdsAt(pos.pos()).mapToObj(this::getGraph).filter(Objects::nonNull)
            .flatMap(g -> g.getNodesAt(pos));
    }

    /**
     * Gets the node holder at the given position.
     *
     * @param pos the position to get the node at.
     * @return the node holder at the given position, if any.
     */
    @Override
    public @Nullable NodeHolder<BlockNode> getNodeAt(@NotNull NodePos pos) {
        BlockGraph graph = getGraphForNode(pos);
        if (graph != null) {
            return graph.getNodeAt(pos);
        }
        return null;
    }

    /**
     * Checks whether the given node with the given position exists.
     *
     * @param pos the positioned node to try to find.
     * @return <code>true</code> if the node was found, <code>false</code> otherwise.
     */
    @Override
    public boolean nodeExistsAt(@NotNull NodePos pos) {
        SimpleBlockGraphChunk chunk = chunks.getIfExists(ChunkSectionPos.from(pos.pos()));
        if (chunk != null) {
            return chunk.containsNode(pos, this::getGraph);
        }
        return false;
    }

    /**
     * Gets the graph id of the given node at the given position.
     *
     * @param pos th positioned node to find the graph id of.
     * @return the graph id of the node, of empty if the node was not found.
     */
    @Override
    public @Nullable SimpleBlockGraph getGraphForNode(@NotNull NodePos pos) {
        SimpleBlockGraphChunk chunk = chunks.getIfExists(ChunkSectionPos.from(pos.pos()));
        if (chunk != null) {
            return chunk.getGraphForNode(pos, this::getGraph);
        }
        return null;
    }

    /**
     * Gets the node entity at the given position, if it exists.
     *
     * @param pos the position to find the node entity at.
     * @return the node entity at the given position, if it exists.
     */
    @Override
    public @Nullable NodeEntity getNodeEntity(@NotNull NodePos pos) {
        BlockGraph graph = getGraphForNode(pos);
        if (graph != null) {
            return graph.getNodeEntity(pos);
        }
        return null;
    }

    /**
     * Checks whether the given link exists.
     *
     * @param pos the position of the link to check.
     * @return <code>true</code> if the given link exists.
     */
    @Override
    public boolean linkExistsAt(@NotNull LinkPos pos) {
        BlockGraph graph = getGraphForNode(pos.first());
        if (graph != null) {
            return graph.linkExistsAt(pos);
        }
        return false;
    }

    /**
     * Gets the link holder at the given position, if it exists.
     *
     * @param pos the position of the link to get.
     * @return the link holder at the given position, if it exists.
     */
    @Override
    public @Nullable LinkHolder<LinkKey> getLinkAt(@NotNull LinkPos pos) {
        BlockGraph graph = getGraphForNode(pos.first());
        if (graph != null) {
            return graph.getLinkAt(pos);
        }
        return null;
    }

    /**
     * Gets the link entity at the given position, if it exists.
     *
     * @param pos the position to find the link entity at.
     * @return the link entity at the given position, if it exists.
     */
    @Override
    public @Nullable LinkEntity getLinkEntity(@NotNull LinkPos pos) {
        BlockGraph graph = getGraphForNode(pos.first());
        if (graph != null) {
            return graph.getLinkEntity(pos);
        }
        return null;
    }

    /**
     * Gets the IDs of all graph with nodes in the given block-position.
     *
     * @param pos the block-position to get the IDs of graphs with nodes at.
     * @return a stream of all the IDs of graphs with nodes in the given block-position.
     */
    @Override
    public @NotNull LongStream getAllGraphIdsAt(@NotNull BlockPos pos) {
        SimpleBlockGraphChunk chunk = chunks.getIfExists(ChunkSectionPos.from(pos));
        if (chunk != null) {
            LongSet graphsInPos = chunk.getGraphsAt(pos);
            if (graphsInPos != null) {
                return graphsInPos.longStream();
            } else {
                return LongStream.empty();
            }
        } else {
            return LongStream.empty();
        }
    }

    /**
     * Gets all loaded graphs at the given position.
     *
     * @param pos the block-position to get the loaded graphs with nodes at.
     * @return all loaded graphs at the given position.
     */
    @Override
    public @NotNull Stream<BlockGraph> getLoadedGraphsAt(@NotNull BlockPos pos) {
        return getAllGraphIdsAt(pos).mapToObj(loadedGraphs::get).filter(Objects::nonNull).map(Function.identity());
    }

    /**
     * Adds a block node and optional node entity at the given position.
     *
     * @param pos           the position and block node to be added.
     * @param entityFactory a factory for potentially creating the node's entity.
     * @return the node created.
     */
    @Override
    public @NotNull NodeHolder<BlockNode> addBlockNode(@NotNull NodePos pos, @NotNull NodeEntityFactory entityFactory) {
        SimpleBlockGraph graph = createGraph(true);
        NodeHolder<BlockNode> node = graph.createNode(pos.pos(), pos.node(), entityFactory);
        updateConnectionsImpl(node);
        return node;
    }

    /**
     * Removes a block node at a position.
     *
     * @param pos the position and block node to be removed.
     * @return <code>true</code> if a node was actually removed, <code>false</code> otherwise.
     */
    @Override
    public boolean removeBlockNode(@NotNull NodePos pos) {
        SimpleBlockGraph graph = getGraphForNode(pos);
        if (graph == null) return false;
        NodeHolder<BlockNode> node = graph.getNodeAt(pos);
        if (node == null) return false;
        graph.destroyNode(node);
        return true;
    }

    /**
     * Connects two nodes to each other.
     * <p>
     * Note: in order for manually connected links to not be removed when the connected nodes are updated,
     * {@link LinkKey#isAutomaticRemoval(LinkHolder)} should return <code>false</code> for the given key.
     *
     * @param a             the first node to be connected.
     * @param b             the second node to be connected.
     * @param key           the key of the connection.
     * @param entityFactory a factory for potentially creating the link's entity.
     * @return the link created, or <code>null</code> if no link could be created.
     */
    @Override
    public @Nullable LinkHolder<LinkKey> connectNodes(@NotNull NodePos a, @NotNull NodePos b, @NotNull LinkKey key,
                                                      @NotNull LinkEntityFactory entityFactory) {
        SimpleBlockGraphChunk aChunk = chunks.getIfExists(ChunkSectionPos.from(a.pos()));
        SimpleBlockGraphChunk bChunk = chunks.getIfExists(ChunkSectionPos.from(b.pos()));

        if (aChunk != null && bChunk != null) {
            SimpleBlockGraph aGraph = aChunk.getGraphForNode(a, this::getGraph);
            SimpleBlockGraph bGraph = bChunk.getGraphForNode(b, this::getGraph);

            if (aGraph != null && bGraph != null) {
                // merge the two graphs if they're not already the same graph
                SimpleBlockGraph mergedGraph;
                if (aGraph.getId() != bGraph.getId()) {
                    if (aGraph.size() >= bGraph.size()) {
                        aGraph.merge(bGraph);
                        mergedGraph = aGraph;
                    } else {
                        bGraph.merge(aGraph);
                        mergedGraph = bGraph;
                    }
                } else {
                    mergedGraph = aGraph;
                }

                NodeHolder<BlockNode> aHolder = mergedGraph.getNodeAt(a);
                NodeHolder<BlockNode> bHolder = mergedGraph.getNodeAt(b);

                if (aHolder == null) {
                    GLLog.warn("Chunk has reference to node {} but the referenced graph does not have that node!", a);

                    // the graph merge was faulty
                    mergedGraph.split();
                    return null;
                }
                if (bHolder == null) {
                    GLLog.warn("Chunk has reference to node {} but the referenced graph does not have that node!", b);

                    // the graph merge was faulty
                    mergedGraph.split();
                    return null;
                }

                LinkHolder<LinkKey> holder = mergedGraph.link(aHolder, bHolder, key, entityFactory);

                // send updated event
                GraphLibEvents.GRAPH_UPDATED.invoker().graphUpdated(world, this, mergedGraph);

                return holder;
            }
        }
        return null;
    }

    /**
     * Disconnects two nodes from each other.
     *
     * @param a   the first node to be disconnected.
     * @param b   the second node to be disconnected.
     * @param key the key of the connection.
     * @return <code>true</code> if a link was actually removed, <code>false</code> otherwise.
     */
    @Override
    public boolean disconnectNodes(@NotNull NodePos a, @NotNull NodePos b, @NotNull LinkKey key) {
        SimpleBlockGraphChunk chunk = chunks.getIfExists(ChunkSectionPos.from(a.pos()));
        if (chunk != null) {
            SimpleBlockGraph graph = chunk.getGraphForNode(a, this::getGraph);
            if (graph != null) {
                NodeHolder<BlockNode> aHolder = graph.getNodeAt(a);
                if (aHolder == null) {
                    GLLog.warn("Chunk has reference to node {} but the referenced graph does not have that node!", a);
                    return false;
                }
                NodeHolder<BlockNode> bHolder = graph.getNodeAt(b);
                if (bHolder == null) {
                    // this just means that 'a' and 'b' were from different graphs and not connected in the first place
                    return false;
                }

                boolean removed = graph.unlink(aHolder, bHolder, key);

                // this sends updated event
                graph.split();

                return removed;
            }
        }
        return false;
    }

    /**
     * Notifies the controller that a block-position has been changed and may need to have its nodes and connections
     * recalculated.
     *
     * @param pos the changed block-position.
     */
    @Override
    public void updateNodes(@NotNull BlockPos pos) {
        nodeUpdates.add(pos.toImmutable());
    }

    /**
     * Notifies the controller that a list of block-positions have been changed and may need to have their nodes and
     * connections recalculated.
     *
     * @param poses the iterable of all the block-positions that might have been changed.
     */
    @Override
    public void updateNodes(@NotNull Iterable<BlockPos> poses) {
        for (BlockPos pos : poses) {
            // I couldn't figure out how to optimise this much, so I'm just calling onChanged for every block-pos
            updateNodes(pos);
        }
    }

    /**
     * Notifies the controller that a list of block-positions have been changed and may need to have their nodes and
     * connections recalculated.
     *
     * @param posStream the stream ob all the block-positions that might have been changed.
     */
    @Override
    public void updateNodes(@NotNull Stream<BlockPos> posStream) {
        posStream.forEach(this::updateNodes);
    }

    /**
     * Updates the connections for all the nodes at the given block-position.
     *
     * @param pos the block-position of the nodes to update connections for.
     */
    @Override
    public void updateConnections(@NotNull BlockPos pos) {
        connectionUpdates.add(new UpdateBlockPos(pos.toImmutable()));
    }

    /**
     * Updates the connections for all the nodes at the given sided block-position.
     *
     * @param pos the sided block-position of the nodes to update connections for.
     */
    @Override
    public void updateConnections(@NotNull SidedPos pos) {
        connectionUpdates.add(new UpdateSidedPos(pos));
    }

    /**
     * Gets the graph with the given ID.
     * <p>
     * Note: this <b>may</b> involve loading the graph from the filesystem.
     *
     * @param id the ID of the graph to get.
     * @return the graph with the given ID.
     */
    @Override
    @Nullable
    public SimpleBlockGraph getGraph(long id) {
        SimpleBlockGraph graph = loadedGraphs.get(id);
        if (graph == null) {
            graph = readGraph(id);
            if (graph != null) {
                loadedGraphs.put(id, graph);
            }
        }

        if (graph != null) {
            for (long posLong : graph.chunks) {
                timer.onChunkUse(ChunkSectionPos.from(posLong));
            }
        }

        return graph;
    }

    /**
     * Gets all graph ids in the given chunk section.
     * <p>
     * Note: Not all graph-ids returned here are guaranteed to belong to valid graphs. {@link #getGraph(long)} may
     * return <code>null</code>.
     *
     * @param pos the position of the chunk section to get the graphs in.
     * @return a stream of all graph ids in the given chunk section.
     */
    @Override
    public @NotNull LongStream getAllGraphIdsInChunkSection(@NotNull ChunkSectionPos pos) {
        SimpleBlockGraphChunk chunk = chunks.getIfExists(pos);
        if (chunk != null) {
            return chunk.getGraphs().longStream();
        } else {
            return LongStream.empty();
        }
    }

    /**
     * Gets all loaded graphs in the given chunk section.
     *
     * @param pos the position of the chunk section to get the loaded graphs in.
     * @return a stream of all the loaded graphs in the given chunk section.
     */
    @Override
    public @NotNull Stream<BlockGraph> getLoadedGraphsInChunkSection(@NotNull ChunkSectionPos pos) {
        return getAllGraphIdsInChunkSection(pos).mapToObj(loadedGraphs::get).filter(Objects::nonNull)
            .map(Function.identity());
    }

    /**
     * Gets all graph ids in the given chunk.
     * <p>
     * Note: Not all graph-ids returned here are guaranteed to belong to valid graphs. {@link #getGraph(long)} may
     * return <code>null</code>.
     *
     * @param pos the position of the chunk to get the graphs in.
     * @return a stream of all graph ids in the given chunk.
     */
    @Override
    public @NotNull LongStream getAllGraphIdsInChunk(@NotNull ChunkPos pos) {
        return LongStream.range(world.getBottomSectionCoord(), world.getTopSectionCoord())
            .flatMap(y -> getAllGraphIdsInChunkSection(ChunkSectionPos.from(pos, (int) y)));
    }

    /**
     * Gets all loaded graphs in the given chunk.
     *
     * @param pos the position of the chunk to get the loaded graphs in.
     * @return a stream of all loaded graphs in the given chunk.
     */
    @Override
    public @NotNull Stream<BlockGraph> getLoadedGraphsInChunk(@NotNull ChunkPos pos) {
        return getAllGraphIdsInChunk(pos).mapToObj(loadedGraphs::get).filter(Objects::nonNull).map(Function.identity());
    }

    /**
     * Gets all graph ids in this graph controller.
     * <p>
     * Note: Not all graph-ids returned here are guaranteed to belong to valid graphs. {@link #getGraph(long)} may
     * return <code>null</code>.
     *
     * @return a stream of all graph ids in this graph controller.
     */
    @Override
    public @NotNull LongStream getAllGraphIds() {
        return getExistingGraphs().longStream();
    }

    /**
     * Gets all currently loaded graphs.
     *
     * @return all the currently loaded graphs.
     */
    @Override
    public @NotNull Stream<BlockGraph> getLoadedGraphs() {
        // Java type variance is broken
        return loadedGraphs.values().stream().map(Function.identity());
    }

    /**
     * Called by the <code>/graphlib removeemptygraphs</code> command.
     * <p>
     * Removes all empty graphs. Graphs should never be empty, but it could theoretically happen if a mod isn't using
     * GraphLib correctly.
     *
     * @return the number of empty graphs removed.
     */
    @Override
    public int removeEmptyGraphs() {
        int removed = 0;

        for (long id : getExistingGraphs()) {
            if (loadedGraphs.containsKey(id)) {
                SimpleBlockGraph graph = loadedGraphs.get(id);

                if (graph.isEmpty()) {
                    GLLog.warn(
                        "Encountered empty graph! The graph's nodes probably failed to load. Removing graph... Id: {}, chunks: {}",
                        graph.getId(), graph.chunks.longStream().mapToObj(ChunkSectionPos::from).toList());

                    // must be impl because destroyGraph calls readGraph if the graph isn't already loaded
                    destroyGraphImpl(graph);

                    removed++;
                }
            } else {
                if (readGraph(id) == null) {
                    removed++;
                }
            }
        }

        return removed;
    }

    // ---- Internal Methods ---- //

    /**
     * Marks a graph as dirty and in need of saving.
     *
     * @param graphId the id of the graph to mark.
     */
    void markDirty(long graphId) {
        unsavedGraphs.add(graphId);
    }

    /**
     * Creates a new graph and stores it, assigning it an ID.
     *
     * @return the newly-created graph.
     */
    @NotNull SimpleBlockGraph createGraph(boolean initializeGraphEntities) {
        SimpleBlockGraph graph = new SimpleBlockGraph(this, getNextGraphId(), initializeGraphEntities);
        loadedGraphs.put(graph.getId(), graph);

        // Fire graph created event
        GraphLibEvents.GRAPH_CREATED.invoker().graphCreated(world, this, graph);

        return graph;
    }

    /**
     * Deletes a graph and all nodes it contains.
     *
     * @param id the ID of the graph to delete.
     */
    void destroyGraph(long id) {
        SimpleBlockGraph graph = getGraph(id);
        if (graph == null) {
            // The graph does not exist.
            GLLog.warn("Attempted to destroy graph that does not exist. Id: {}", id);
            return;
        }

        destroyGraphImpl(graph);

        // Fire the event
        GraphLibEvents.GRAPH_DESTROYED.invoker().graphDestroyed(world, this, id);
    }

    void putGraphWithNode(long id, @NotNull NodePos pos) {
        ChunkSectionPos sectionPos = ChunkSectionPos.from(pos.pos());
        SimpleBlockGraphChunk chunk = chunks.getOrCreate(sectionPos);
        chunk.putGraphWithNode(id, pos, this::getGraph);

        timer.onChunkUse(sectionPos);
    }

    void removeGraphWithNode(long id, @NotNull NodePos pos) {
        ChunkSectionPos sectionPos = ChunkSectionPos.from(pos.pos());
        SimpleBlockGraphChunk chunk = chunks.getIfExists(sectionPos);
        if (chunk != null) {
            chunk.removeGraphWithNodeUnchecked(pos);
        } else {
            GLLog.warn("Tried to remove node fom non-existent chunk. Id: {}, chunk: {}, node: {}", id, sectionPos, pos);
        }
    }

    void removeGraphInPos(long id, @NotNull BlockPos pos) {
        ChunkSectionPos sectionPos = ChunkSectionPos.from(pos);
        SimpleBlockGraphChunk chunk = chunks.getIfExists(sectionPos);
        if (chunk != null) {
            chunk.removeGraphInPosUnchecked(id, pos);
        } else {
            GLLog.warn("Tried to remove graph from non-existent chunk. Id: {}, chunk: {}, block: {}", id, sectionPos,
                pos);
        }
    }

    void removeGraphInChunk(long id, long pos) {
        ChunkSectionPos sectionPos = ChunkSectionPos.from(pos);
        SimpleBlockGraphChunk chunk = chunks.getIfExists(sectionPos);
        if (chunk != null) {
            chunk.removeGraphUnchecked(id);
        } else {
            GLLog.warn("Tried to remove graph fom non-existent chunk. Id: {}, chunk: {}", id, sectionPos);
        }
    }

    void removeGraphInPoses(long id, @NotNull Iterable<NodePos> nodes, @NotNull Iterable<BlockPos> poses,
                            @NotNull LongIterable chunkPoses) {
        for (NodePos node : nodes) {
            removeGraphWithNode(id, node);
        }
        for (BlockPos pos : poses) {
            removeGraphInPos(id, pos);
        }
        for (long pos : chunkPoses) {
            removeGraphInChunk(id, pos);
        }
    }

    // ---- Node Update Methods ---- //

    private void handleNodeUpdates() {
        for (BlockPos pos : nodeUpdates) {
            Set<BlockNode> nodes = universe.discoverNodesInBlock(world, pos);
            onNodesChanged(pos, nodes);
        }
        nodeUpdates.clear();
    }

    private void handleConnectionUpdates() {
        for (UpdatePos pos : connectionUpdates) {
            if (pos instanceof UpdateBlockPos blockPos) {
                for (var node : getNodesAt(blockPos.pos).toList()) {
                    updateConnectionsImpl(node);
                }
            } else if (pos instanceof UpdateSidedPos sidedPos) {
                for (var node : getNodesAt(sidedPos.pos).toList()) {
                    updateConnectionsImpl(node.cast(BlockNode.class));
                }
            }
        }
        connectionUpdates.clear();
    }

    void scheduleCallbackUpdate(@NotNull NodeHolder<BlockNode> node) {
        //noinspection ConstantConditions
        if (node == null) {
            GLLog.error("Something tried to schedule an update for a NULL node! This should NEVER happen.",
                new RuntimeException("Stack Trace"));
            return;
        }

        callbackUpdates.add(node);
    }

    private void handleCallbackUpdates() {
        for (var node : callbackUpdates) {
            node.getNode().onConnectionsChanged(node);
        }
        callbackUpdates.clear();
    }

    // ---- Private Methods ---- //

    private void onNodesChanged(@NotNull BlockPos pos, @NotNull Set<BlockNode> nodes) {
        Set<BlockNode> newNodes = new LinkedHashSet<>(nodes);

        for (long graphId : getAllGraphIdsAt(pos).toArray()) {
            SimpleBlockGraph graph = getGraph(graphId);
            if (graph == null) {
                GLLog.warn("Encountered invalid graph in position when detecting node changes. Id: {}, pos: {}",
                    graphId, pos);
                continue;
            }

            for (var node : graph.getNodesAt(pos).toList()) {
                BlockNode bn = node.getNode();
                if (bn.isAutomaticRemoval(node) && !nodes.contains(bn)) {
                    graph.destroyNode(node);
                }
                newNodes.remove(bn);
            }
        }

        for (BlockNode bn : newNodes) {
            if (bn == null) {
                GLLog.warn("Something tried to add a null BlockNode! Ignoring... Pos: {}", pos,
                    new RuntimeException("Stack Trace"));
                continue;
            }

            SimpleBlockGraph newGraph = createGraph(true);
            NodeHolder<BlockNode> node = newGraph.createNode(pos, bn, bn::createNodeEntity);
            updateConnectionsImpl(node);
        }
    }

    private void updateConnectionsImpl(@NotNull NodeHolder<BlockNode> node) {
        long nodeGraphId = node.getGraphId();
        SimpleBlockGraph graph = getGraph(nodeGraphId);

        if (graph == null) {
            GLLog.warn("Tried to update node with invalid graph id. Node: {}", node);
            return;
        }

        // Collect the old node connections
        Map<HalfLink, LinkHolder<LinkKey>> oldConnections = new Object2ObjectLinkedOpenHashMap<>();
        for (LinkHolder<LinkKey> link : node.getConnections()) {
            oldConnections.put(link.toHalfLink(node), link);
        }

        // Collect the new connections that are wanted by both parties
        Set<HalfLink> wantedConnections = new ObjectLinkedOpenHashSet<>();
        for (HalfLink wanted : node.getNode().findConnections(node)) {
            NodeHolder<BlockNode> other = wanted.other();
            if (other.getNode().canConnect(other, wanted.reverse(node))) {
                wantedConnections.add(wanted);
            }
        }

        // Collect the new wanted connections we don't have yet
        List<HalfLink> newConnections = new ObjectArrayList<>();
        for (HalfLink wanted : wantedConnections) {
            NodeHolder<BlockNode> other = wanted.other();
            if (other.getGraphId() != nodeGraphId || !oldConnections.containsKey(wanted)) {
                newConnections.add(wanted);
            }
        }

        // Collect the removed connections
        List<HalfLink> removedConnections = new ObjectArrayList<>();
        for (Map.Entry<HalfLink, LinkHolder<LinkKey>> entry : oldConnections.entrySet()) {
            HalfLink old = entry.getKey();
            LinkHolder<LinkKey> link = entry.getValue();
            if (link.getKey().isAutomaticRemoval(link)) {
                // automatic removal means that not being wanted causes removal
                if (!wantedConnections.contains(old)) {
                    removedConnections.add(old);
                }
            } else {
                // if the other end has been removed, we still want to remove the manual link
                if (!nodeExistsAt(old.other().toNodePos())) {
                    removedConnections.add(old);
                }
            }
        }

        long mergedGraphId = nodeGraphId;
        SimpleBlockGraph mergedGraph = graph;

        for (var link : newConnections) {
            long otherGraphId = link.other().getGraphId();
            if (otherGraphId != mergedGraphId) {
                SimpleBlockGraph otherGraph = getGraph(otherGraphId);
                if (otherGraph == null) {
                    GLLog.warn("Tried to connect to node with invalid graph id. Half-link: {}", link);
                    continue;
                }

                // merge the smaller graph into the larger graph
                if (otherGraph.size() > mergedGraph.size()) {
                    // merge self graph into the other graph because that would mean less node moves
                    otherGraph.merge(mergedGraph);
                    mergedGraph = otherGraph;
                    mergedGraphId = otherGraphId;
                } else {
                    mergedGraph.merge(otherGraph);
                }
            }

            mergedGraph.link(node, link.other(), link.key(), link.key()::createLinkEntity);
        }

        for (var link : removedConnections) {
            mergedGraph.unlink(node, link.other(), link.key());
        }

        if (!removedConnections.isEmpty()) {
            // Split should never leave graph empty. It also should clean up after itself.
            mergedGraph.split();
        } else {
            GraphLibEvents.GRAPH_UPDATED.invoker().graphUpdated(world, this, mergedGraph);
        }
    }

    private void tickGraphs() {
        for (SimpleBlockGraph graph : loadedGraphs.values()) {
            graph.onTick();
        }
    }

    private void loadGraphs(@NotNull ChunkPos pos) {
        for (int y = world.getBottomSectionCoord(); y < world.getTopSectionCoord(); y++) {
            SimpleBlockGraphChunk chunk = chunks.getIfExists(ChunkSectionPos.from(pos.x, y, pos.z));
            if (chunk != null) {
                for (long id : chunk.getGraphs()) {
                    getGraph(id);
                }
            }
        }
    }

    private void saveGraphs(@NotNull ChunkPos pos) {
        LongSet chunkSectionPillar = new LongOpenHashSet(world.getTopSectionCoord() - world.getBottomSectionCoord());
        for (int y = world.getBottomSectionCoord(); y < world.getTopSectionCoord(); y++) {
            chunkSectionPillar.add(ChunkSectionPos.asLong(pos.x, y, pos.z));
        }

        for (SimpleBlockGraph loadedGraph : loadedGraphs.values()) {
            for (long graphChunk : loadedGraph.chunks) {
                if (chunkSectionPillar.contains(graphChunk)) {
                    writeGraph(loadedGraph);
                    unsavedGraphs.remove(loadedGraph.getId());
                    break;
                }
            }
        }
    }

    private void unloadGraphs() {
        List<ChunkSectionPos> chunksToUnload = timer.chunksToUnload();
        for (ChunkSectionPos chunk : chunksToUnload) {
            // acknowledge that we're unloading the chunk's data
            timer.onChunkUnload(chunk);
        }

        if (!chunksToUnload.isEmpty()) {
            LongSet toUnload = new LongLinkedOpenHashSet();

            for (SimpleBlockGraph graph : loadedGraphs.values()) {
                // we want to only unload graphs that aren't in any loaded chunks
                if (graph.chunks.longStream().mapToObj(ChunkSectionPos::from).noneMatch(timer::isChunkLoaded)) {
                    toUnload.add(graph.getId());
                }
            }

            for (long id : toUnload) {
                // unload the graphs
                SimpleBlockGraph graph = loadedGraphs.remove(id);
                GraphLibEvents.GRAPH_UNLOADING.invoker().graphUnloading(world, this, graph);
                graph.onUnload();
                writeGraph(graph);
                unsavedGraphs.remove(id);
            }
        }
    }

    private void saveUnsvedGraphs() {
        if (unsavedGraphs.isEmpty() || !(saveMode == SaveMode.INCREMENTAL || saveMode == SaveMode.IMMEDIATE)) return;

        int saveCount;
        if (saveMode == SaveMode.IMMEDIATE) {
            saveCount = unsavedGraphs.size();
        } else {
            saveCount = (unsavedGraphs.size() + INCREMENTAL_SAVE_FACTOR - 1) / INCREMENTAL_SAVE_FACTOR;
        }

        LongIterator iter = unsavedGraphs.longIterator();
        while (iter.hasNext() && saveCount > 0) {
            SimpleBlockGraph graph = loadedGraphs.get(iter.nextLong());

            if (graph != null) {
                writeGraph(graph);
                saveCount--;
            }

            iter.remove();
        }
    }

    private void saveAllGraphs() {
        for (SimpleBlockGraph graph : loadedGraphs.values()) {
            writeGraph(graph);
        }
        unsavedGraphs.clear();
    }

    private long getNextGraphId() {
        do {
            prevGraphId++;
        } while (graphExists(prevGraphId));
        markStateDirty();
        return prevGraphId;
    }

    private boolean graphExists(long id) {
        return loadedGraphs.containsKey(id) || Files.exists(getGraphFile(id));
    }

    private @NotNull Path getGraphFile(long id) {
        return graphsDir.resolve(String.format("%016X.dat", id));
    }

    private static final Pattern GRAPH_ID_PATTERN = Pattern.compile("^(?<id>[\\da-fA-F]+)\\.dat$");

    private @NotNull LongList getExistingGraphs() {
        LongList ids = new LongArrayList();
        ids.addAll(loadedGraphs.keySet());

        try (Stream<Path> children = Files.list(graphsDir)) {
            children.forEach(child -> {
                String filename = child.getFileName().toString();
                Matcher matcher = GRAPH_ID_PATTERN.matcher(filename);

                if (matcher.matches()) {
                    try {
                        long id = Long.parseLong(matcher.group("id"), 16);
                        ids.add(id);
                    } catch (NumberFormatException e) {
                        GLLog.warn("Encountered NumberFormatException while parsing graph id from filename: {}",
                            filename, e);
                    }
                } else {
                    GLLog.warn("Encountered non-graph file in graphs dir: {}", child);
                }
            });
        } catch (IOException e) {
            GLLog.error("Error listing children of {}", graphsDir, e);
        }

        return ids;
    }

    private void writeGraph(@NotNull SimpleBlockGraph graph) {
        Path graphFile = getGraphFile(graph.getId());

        NbtCompound root = new NbtCompound();
        root.put("data", graph.toTag());

        try (OutputStream os = Files.newOutputStream(graphFile)) {
            NbtIo.writeCompressed(root, os);
        } catch (IOException e) {
            GLLog.error("Unable to save graph {}.", graph.getId(), e);
        }
    }

    @Nullable
    private SimpleBlockGraph readGraph(long id) {
        Path graphFile = getGraphFile(id);

        if (!Files.exists(graphFile)) {
            return null;
        }

        try (InputStream is = Files.newInputStream(graphFile)) {
            NbtCompound root = NbtIo.readCompressed(is);
            NbtCompound data = root.getCompound("data");
            SimpleBlockGraph graph = SimpleBlockGraph.fromTag(this, id, data);
            if (graph.isEmpty()) {
                GLLog.warn(
                    "Loaded empty graph! The graph's nodes probably failed to load. Removing graph... Id: {}, chunks: {}",
                    graph.getId(), graph.chunks.longStream().mapToObj(ChunkSectionPos::from).toList());

                // must be impl because destroyGraph calls readGraph if the graph isn't already loaded
                destroyGraphImpl(graph);
                return null;
            } else {
                return graph;
            }
        } catch (IOException e) {
            GLLog.error("Unable to load graph {}. Removing graph...", id, e);

            if (Files.exists(graphFile)) {
                try {
                    Files.delete(graphFile);
                } catch (IOException ex) {
                    GLLog.error("Error deleting broken graph file: {}", graphFile, ex);
                }
            }

            return null;
        }
    }

    private void destroyGraphImpl(SimpleBlockGraph graph) {
        long id = graph.getId();

        loadedGraphs.remove(id);
        unsavedGraphs.remove(id);
        try {
            Files.deleteIfExists(getGraphFile(id));
        } catch (IOException e) {
            GLLog.error("Error removing graph file. Id: {}", id, e);
        }

        for (long sectionPos : graph.chunks) {
            SimpleBlockGraphChunk chunk = chunks.getIfExists(ChunkSectionPos.from(sectionPos));
            if (chunk != null) {
                // Note: if this is changed to only remove from block-poses that the graph actually occupies, make sure
                // not to get those block-poses from the block-graph's graph, because the block-graph's graph will often
                // already have been cleared by the time this function is called.
                chunk.removeGraph(id);
            } else {
                GLLog.warn("Attempted to destroy graph in chunk that does not exist. Id: {}, chunk: {}", id,
                    ChunkSectionPos.from(sectionPos));
            }
        }

        graph.onDestroy();
    }

    private void markStateDirty() {
        stateDirty = true;
    }

    private void loadState() {
        if (Files.exists(stateFile)) {
            try (InputStream is = Files.newInputStream(stateFile)) {
                NbtCompound root = NbtIo.readCompressed(is);
                NbtCompound data = root.getCompound("data");
                prevGraphId = data.getLong("prevGraphId");
            } catch (Exception e) {
                GLLog.error("Error loading graph controller state file.", e);
            }
        }
    }

    private void saveState() {
        if (stateDirty) {
            NbtCompound root = new NbtCompound();

            NbtCompound data = new NbtCompound();
            data.putLong("prevGraphId", prevGraphId);

            root.put("data", data);

            if (!Files.exists(stateFile.getParent())) {
                try {
                    Files.createDirectories(stateFile.getParent());
                } catch (IOException e) {
                    GLLog.error("Error creating graph controller state save directory.", e);
                }
            }

            try (OutputStream os = Files.newOutputStream(stateFile)) {
                NbtIo.writeCompressed(root, os);
            } catch (IOException e) {
                GLLog.error("Error saving graph controller state.", e);
                return;
            }

            stateDirty = false;
        }
    }

    private sealed interface UpdatePos {
    }

    private record UpdateBlockPos(BlockPos pos) implements UpdatePos {
    }

    private record UpdateSidedPos(SidedPos pos) implements UpdatePos {
    }
}
