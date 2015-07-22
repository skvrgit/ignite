/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.distributed.dht.atomic;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.cluster.*;
import org.apache.ignite.internal.processors.affinity.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.distributed.dht.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.util.future.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.jetbrains.annotations.*;
import org.jsr166.*;

import javax.cache.processor.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.cache.CacheWriteSynchronizationMode.*;

/**
 * DHT atomic cache backup update future.
 */
public class GridDhtAtomicUpdateFuture extends GridFutureAdapter<Void>
    implements GridCacheAtomicFuture<Void> {
    /** */
    private static final long serialVersionUID = 0L;

    /** Logger reference. */
    private static final AtomicReference<IgniteLogger> logRef = new AtomicReference<>();

    /** Logger. */
    protected static IgniteLogger log;

    /** Cache context. */
    private GridCacheContext cctx;

    /** Future version. */
    private GridCacheVersion futVer;

    /** Write version. */
    private GridCacheVersion writeVer;

    /** Force transform backup flag. */
    private boolean forceTransformBackups;

    /** Completion callback. */
    @GridToStringExclude
    private CI2<GridNearAtomicUpdateRequest, GridNearAtomicUpdateResponse> completionCb;

    /** Mappings. */
    @GridToStringInclude
    private ConcurrentMap<GridAtomicMappingKey, GridDhtAtomicUpdateRequest> mappings = new ConcurrentHashMap8<>();

    /** Entries with readers. */
    private Map<KeyCacheObject, GridDhtCacheEntry> nearReadersEntries;

    /** Update request. */
    private GridNearAtomicUpdateRequest updateReq;

    /** Update response. */
    private GridNearAtomicUpdateResponse updateRes;

    /** Future keys. */
    private Collection<KeyCacheObject> keys;

    /** */
    private boolean waitForExchange;

    /**
     * @param cctx Cache context.
     * @param completionCb Callback to invoke when future is completed.
     * @param writeVer Write version.
     * @param updateReq Update request.
     * @param updateRes Update response.
     */
    public GridDhtAtomicUpdateFuture(
        GridCacheContext cctx,
        CI2<GridNearAtomicUpdateRequest,
        GridNearAtomicUpdateResponse> completionCb,
        GridCacheVersion writeVer,
        GridNearAtomicUpdateRequest updateReq,
        GridNearAtomicUpdateResponse updateRes
    ) {
        this.cctx = cctx;
        this.writeVer = writeVer;

        futVer = cctx.versions().next(updateReq.topologyVersion());
        this.updateReq = updateReq;
        this.completionCb = completionCb;
        this.updateRes = updateRes;

        if (log == null)
            log = U.logger(cctx.kernalContext(), logRef, GridDhtAtomicUpdateFuture.class);

        keys = new ArrayList<>(updateReq.keys().size());

        boolean topLocked = updateReq.topologyLocked() || (updateReq.fastMap() && !updateReq.clientRequest());

        waitForExchange = !topLocked;
    }

    /** {@inheritDoc} */
    @Override public IgniteUuid futureId() {
        return futVer.asGridUuid();
    }

    /** {@inheritDoc} */
    @Override public GridCacheVersion version() {
        return futVer;
    }

    /** {@inheritDoc} */
    @Override public Collection<? extends ClusterNode> nodes() {
        return F.view(F.viewReadOnly(mappings.keySet(), new C1<GridAtomicMappingKey, ClusterNode>() {
            @Override public ClusterNode apply(GridAtomicMappingKey mappingKey) {
                return cctx.kernalContext().discovery().node(mappingKey.nodeId());
            }
        }), F.notNull());
    }

    /** {@inheritDoc} */
    @Override public boolean onNodeLeft(UUID nodeId) {
        if (log.isDebugEnabled())
            log.debug("Processing node leave event [fut=" + this + ", nodeId=" + nodeId + ']');

        Collection<GridAtomicMappingKey> mappingKeys = new ArrayList<>(mappings.size());

        for (GridAtomicMappingKey mappingKey : mappings.keySet()) {
            if (mappingKey.nodeId().equals(nodeId))
                mappingKeys.add(mappingKey);
        }

        if (!mappingKeys.isEmpty()) {
            for (GridAtomicMappingKey mappingKey : mappingKeys)
                mappings.remove(mappingKey);

            checkComplete();

            return true;
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean trackable() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public void markNotTrackable() {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public AffinityTopologyVersion topologyVersion() {
        return updateReq.topologyVersion();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Void> completeFuture(AffinityTopologyVersion topVer) {
        if (waitForExchange && topologyVersion().compareTo(topVer) < 0)
            return this;

        return null;
    }

    /** {@inheritDoc} */
    @Override public Collection<KeyCacheObject> keys() {
        return keys;
    }

    /**
     * @param entry Entry to map.
     * @param val Value to write.
     * @param entryProcessor Entry processor.
     * @param ttl TTL (optional).
     * @param conflictExpireTime Conflict expire time (optional).
     * @param conflictVer Conflict version (optional).
     */
    public void addWriteEntry(GridDhtCacheEntry entry,
        @Nullable CacheObject val,
        EntryProcessor<Object, Object, Object> entryProcessor,
        long ttl,
        long conflictExpireTime,
        @Nullable GridCacheVersion conflictVer) {
        AffinityTopologyVersion topVer = updateReq.topologyVersion();

        int part = entry.partition();

        Collection<ClusterNode> dhtNodes = cctx.dht().topology().nodes(part, topVer);

        if (!cctx.config().isAtomicOrderedUpdates())
            part = -1;

        if (log.isDebugEnabled())
            log.debug("Mapping entry to DHT nodes [nodes=" + U.nodeIds(dhtNodes) + ", entry=" + entry + ']');

        CacheWriteSynchronizationMode syncMode = updateReq.writeSynchronizationMode();

        keys.add(entry.key());

        for (ClusterNode node : dhtNodes) {
            UUID nodeId = node.id();

            GridAtomicMappingKey mappingKey = new GridAtomicMappingKey(nodeId, part);

            if (!nodeId.equals(cctx.localNodeId())) {
                GridDhtAtomicUpdateRequest updateReq = mappings.get(mappingKey);

                if (updateReq == null) {
                    updateReq = new GridDhtAtomicUpdateRequest(
                        cctx.cacheId(),
                        nodeId,
                        futVer,
                        writeVer,
                        syncMode,
                        topVer,
                        forceTransformBackups,
                        this.updateReq.subjectId(),
                        this.updateReq.taskNameHash(),
                        forceTransformBackups ? this.updateReq.invokeArguments() : null,
                        part);

                    mappings.put(mappingKey, updateReq);
                }

                updateReq.addWriteValue(entry.key(),
                    val,
                    entryProcessor,
                    ttl,
                    conflictExpireTime,
                    conflictVer);
            }
        }
    }

    /**
     * @param readers Entry readers.
     * @param entry Entry.
     * @param val Value.
     * @param entryProcessor Entry processor..
     * @param ttl TTL for near cache update (optional).
     * @param expireTime Expire time for near cache update (optional).
     */
    public void addNearWriteEntries(Iterable<UUID> readers,
        GridDhtCacheEntry entry,
        @Nullable CacheObject val,
        EntryProcessor<Object, Object, Object> entryProcessor,
        long ttl,
        long expireTime) {
        CacheWriteSynchronizationMode syncMode = updateReq.writeSynchronizationMode();

        keys.add(entry.key());

        AffinityTopologyVersion topVer = updateReq.topologyVersion();

        int part = cctx.config().isAtomicOrderedUpdates() ? entry.partition() : -1;

        for (UUID nodeId : readers) {
            GridAtomicMappingKey mappingKey = new GridAtomicMappingKey(nodeId, part);

            GridDhtAtomicUpdateRequest updateReq = mappings.get(mappingKey);

            if (updateReq == null) {
                ClusterNode node = cctx.discovery().node(nodeId);

                // Node left the grid.
                if (node == null)
                    continue;

                updateReq = new GridDhtAtomicUpdateRequest(
                    cctx.cacheId(),
                    nodeId,
                    futVer,
                    writeVer,
                    syncMode,
                    topVer,
                    forceTransformBackups,
                    this.updateReq.subjectId(),
                    this.updateReq.taskNameHash(),
                    forceTransformBackups ? this.updateReq.invokeArguments() : null,
                    part);

                mappings.put(mappingKey, updateReq);
            }

            if (nearReadersEntries == null)
                nearReadersEntries = new HashMap<>();

            nearReadersEntries.put(entry.key(), entry);

            updateReq.addNearWriteValue(entry.key(),
                val,
                entryProcessor,
                ttl,
                expireTime);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean onDone(@Nullable Void res, @Nullable Throwable err) {
        if (super.onDone(res, err)) {
            cctx.mvcc().removeAtomicFuture(version());

            if (updateReq.writeSynchronizationMode() == FULL_SYNC)
                completionCb.apply(updateReq, updateRes);

            return true;
        }

        return false;
    }

    /**
     * Sends requests to remote nodes.
     */
    public void map() {
        if (!mappings.isEmpty()) {
            for (Map.Entry<GridAtomicMappingKey, GridDhtAtomicUpdateRequest> e : mappings.entrySet()) {
                GridAtomicMappingKey mappingKey = e.getKey();
                GridDhtAtomicUpdateRequest req = e.getValue();

                try {
                    if (log.isDebugEnabled())
                        log.debug("Sending DHT atomic update request [nodeId=" + req.nodeId() + ", req=" + req + ']');

                    if (mappingKey.partition() >= 0) {
                        Object topic = CU.partitionMessageTopic(cctx, mappingKey.partition(), false);

                        cctx.io().sendOrderedMessage(mappingKey.nodeId(), topic, req, cctx.ioPolicy(),
                            2 * cctx.gridConfig().getNetworkTimeout());
                    }
                    else {
                        assert mappingKey.partition() == -1;

                        cctx.io().send(req.nodeId(), req, cctx.ioPolicy());
                    }
                }
                catch (ClusterTopologyCheckedException ignored) {
                    U.warn(log, "Failed to send update request to backup node because it left grid: " +
                        req.nodeId());

                    mappings.remove(mappingKey);
                }
                catch (IgniteCheckedException ex) {
                    U.error(log, "Failed to send update request to backup node (did node leave the grid?): "
                        + req.nodeId(), ex);

                    mappings.remove(mappingKey);
                }
            }
        }

        checkComplete();

        // Send response right away if no ACKs from backup is required.
        // Backups will send ACKs anyway, future will be completed after all backups have replied.
        if (updateReq.writeSynchronizationMode() != FULL_SYNC)
            completionCb.apply(updateReq, updateRes);
    }

    /**
     * Callback for backup update response.
     *
     * @param nodeId Backup node ID.
     * @param updateRes Update response.
     */
    public void onResult(UUID nodeId, GridDhtAtomicUpdateResponse updateRes) {
        if (log.isDebugEnabled())
            log.debug("Received DHT atomic update future result [nodeId=" + nodeId + ", updateRes=" + updateRes + ']');

        if (updateRes.error() != null)
            this.updateRes.addFailedKeys(updateRes.failedKeys(), updateRes.error());

        if (!F.isEmpty(updateRes.nearEvicted())) {
            for (KeyCacheObject key : updateRes.nearEvicted()) {
                GridDhtCacheEntry entry = nearReadersEntries.get(key);

                try {
                    entry.removeReader(nodeId, updateRes.messageId());
                }
                catch (GridCacheEntryRemovedException e) {
                    if (log.isDebugEnabled())
                        log.debug("Entry with evicted reader was removed [entry=" + entry + ", err=" + e + ']');
                }
            }
        }

        mappings.remove(new GridAtomicMappingKey(nodeId, updateRes.partition()));

        checkComplete();
    }

    /**
     * Deferred update response.
     *
     * @param nodeId Backup node ID.
     * @param res Response.
     */
    public void onResult(UUID nodeId, GridDhtAtomicDeferredUpdateResponse res) {
        if (log.isDebugEnabled())
            log.debug("Received deferred DHT atomic update future result [nodeId=" + nodeId + ']');

        for (Integer part : res.partitions())
            mappings.remove(new GridAtomicMappingKey(nodeId, part));

        checkComplete();
    }

    /**
     * Checks if all required responses are received.
     */
    private void checkComplete() {
        // Always wait for replies from all backups.
        if (mappings.isEmpty()) {
            if (log.isDebugEnabled())
                log.debug("Completing DHT atomic update future: " + this);

            onDone();
        }
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridDhtAtomicUpdateFuture.class, this);
    }

}
