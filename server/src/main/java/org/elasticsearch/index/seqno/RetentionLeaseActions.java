/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.seqno;

import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.single.shard.SingleShardRequest;
import org.elasticsearch.action.support.single.shard.TransportSingleShardAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.PlainShardIterator;
import org.elasticsearch.cluster.routing.ShardsIterator;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * This class holds all actions related to retention leases. Note carefully that these actions are executed under a primary permit. Care is
 * taken to thread the listener through the invocations so that for the sync APIs we do not notify the listener until these APIs have
 * responded with success. Additionally, note the use of
 * {@link TransportSingleShardAction#asyncShardOperation(SingleShardRequest, ShardId, ActionListener)} to handle the case when acquiring
 * permits goes asynchronous because acquiring permits is blocked
 */
public class RetentionLeaseActions {

    public static final long RETAIN_ALL = -1;

    abstract static class TransportRetentionLeaseAction<T extends Request<T>> extends TransportSingleShardAction<T, Response> {

        private final IndicesService indicesService;

        @Inject
        TransportRetentionLeaseAction(
                final String name,
                final ThreadPool threadPool,
                final ClusterService clusterService,
                final TransportService transportService,
                final ActionFilters actionFilters,
                final IndexNameExpressionResolver indexNameExpressionResolver,
                final IndicesService indicesService,
                final Supplier<T> requestSupplier) {
            super(
                    name,
                    threadPool,
                    clusterService,
                    transportService,
                    actionFilters,
                    indexNameExpressionResolver,
                    requestSupplier,
                    ThreadPool.Names.MANAGEMENT);
            this.indicesService = Objects.requireNonNull(indicesService);
        }

        @Override
        protected ShardsIterator shards(final ClusterState state, final InternalRequest request) {
            final IndexShardRoutingTable shardRoutingTable = state
                    .routingTable()
                    .shardRoutingTable(request.concreteIndex(), request.request().getShardId().id());
            if (shardRoutingTable.primaryShard().active()) {
                return shardRoutingTable.primaryShardIt();
            } else {
                return new PlainShardIterator(request.request().getShardId(), Collections.emptyList());
            }
        }

        @Override
        protected void asyncShardOperation(T request, ShardId shardId, final ActionListener<Response> listener) {
            final IndexService indexService = indicesService.indexServiceSafe(shardId.getIndex());
            final IndexShard indexShard = indexService.getShard(shardId.id());
            indexShard.acquirePrimaryOperationPermit(
                ActionListener.delegateFailure(listener, (delegatedListener, releasable) -> {
                    try (Releasable ignore = releasable) {
                        doRetentionLeaseAction(indexShard, request, delegatedListener);
                    }
                }),
                ThreadPool.Names.SAME,
                request);
        }

        @Override
        protected Response shardOperation(final T request, final ShardId shardId) {
            throw new UnsupportedOperationException();
        }

        abstract void doRetentionLeaseAction(IndexShard indexShard, T request, ActionListener<Response> listener);

        @Override
        protected Response newResponse() {
            return new Response();
        }

        @Override
        protected boolean resolveIndex(final T request) {
            return false;
        }

    }

    public static class Add extends Action<Response> {

        public static final Add INSTANCE = new Add();
        public static final String ACTION_NAME = "indices:admin/seq_no/add_retention_lease";

        private Add() {
            super(ACTION_NAME);
        }

        public static class TransportAction extends TransportRetentionLeaseAction<AddRequest> {

            @Inject
            public TransportAction(
                    final ThreadPool threadPool,
                    final ClusterService clusterService,
                    final TransportService transportService,
                    final ActionFilters actionFilters,
                    final IndexNameExpressionResolver indexNameExpressionResolver,
                    final IndicesService indicesService) {
                super(
                        ACTION_NAME,
                        threadPool,
                        clusterService,
                        transportService,
                        actionFilters,
                        indexNameExpressionResolver,
                        indicesService,
                        AddRequest::new);
            }

            @Override
            void doRetentionLeaseAction(final IndexShard indexShard, final AddRequest request, final ActionListener<Response> listener) {
                indexShard.addRetentionLease(
                        request.getId(),
                        request.getRetainingSequenceNumber(),
                        request.getSource(),
                        ActionListener.map(listener, r -> new Response()));
            }

        }

        @Override
        public Response newResponse() {
            return new Response();
        }

    }

    public static class Renew extends Action<Response> {

        public static final Renew INSTANCE = new Renew();
        public static final String ACTION_NAME = "indices:admin/seq_no/renew_retention_lease";

        private Renew() {
            super(ACTION_NAME);
        }

        public static class TransportAction extends TransportRetentionLeaseAction<RenewRequest> {

            @Inject
            public TransportAction(
                    final ThreadPool threadPool,
                    final ClusterService clusterService,
                    final TransportService transportService,
                    final ActionFilters actionFilters,
                    final IndexNameExpressionResolver indexNameExpressionResolver,
                    final IndicesService indicesService) {
                super(
                        ACTION_NAME,
                        threadPool,
                        clusterService,
                        transportService,
                        actionFilters,
                        indexNameExpressionResolver,
                        indicesService,
                        RenewRequest::new);
            }


            @Override
            void doRetentionLeaseAction(final IndexShard indexShard, final RenewRequest request, final ActionListener<Response> listener) {
                indexShard.renewRetentionLease(request.getId(), request.getRetainingSequenceNumber(), request.getSource());
                listener.onResponse(new Response());
            }

        }

        @Override
        public Response newResponse() {
            return new Response();
        }

    }

    public static class Remove extends Action<Response> {

        public static final Remove INSTANCE = new Remove();
        public static final String ACTION_NAME = "indices:admin/seq_no/remove_retention_lease";

        private Remove() {
            super(ACTION_NAME);
        }

        public static class TransportAction extends TransportRetentionLeaseAction<RemoveRequest> {

            @Inject
            public TransportAction(
                    final ThreadPool threadPool,
                    final ClusterService clusterService,
                    final TransportService transportService,
                    final ActionFilters actionFilters,
                    final IndexNameExpressionResolver indexNameExpressionResolver,
                    final IndicesService indicesService) {
                super(
                        ACTION_NAME,
                        threadPool,
                        clusterService,
                        transportService,
                        actionFilters,
                        indexNameExpressionResolver,
                        indicesService,
                        RemoveRequest::new);
            }


            @Override
            void doRetentionLeaseAction(final IndexShard indexShard, final RemoveRequest request, final ActionListener<Response> listener) {
                indexShard.removeRetentionLease(
                        request.getId(),
                        ActionListener.map(listener, r -> new Response()));
            }

        }

        @Override
        public Response newResponse() {
            return new Response();
        }

    }

    private abstract static class Request<T extends SingleShardRequest<T>> extends SingleShardRequest<T> {

        private ShardId shardId;

        public ShardId getShardId() {
            return shardId;
        }

        private String id;

        public String getId() {
            return id;
        }

        Request() {
        }

        Request(final ShardId shardId, final String id) {
            super(Objects.requireNonNull(shardId).getIndexName());
            this.shardId = shardId;
            this.id = Objects.requireNonNull(id);
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void readFrom(final StreamInput in) throws IOException {
            super.readFrom(in);
            shardId = new ShardId(in);
            id = in.readString();
        }

        @Override
        public void writeTo(final StreamOutput out) throws IOException {
            super.writeTo(out);
            shardId.writeTo(out);
            out.writeString(id);
        }

    }

    private abstract static class AddOrRenewRequest<T extends SingleShardRequest<T>> extends Request<T> {

        private long retainingSequenceNumber;

        public long getRetainingSequenceNumber() {
            return retainingSequenceNumber;
        }

        private String source;

        public String getSource() {
            return source;
        }

        AddOrRenewRequest() {
        }

        AddOrRenewRequest(final ShardId shardId, final String id, final long retainingSequenceNumber, final String source) {
            super(shardId, id);
            if (retainingSequenceNumber < 0 && retainingSequenceNumber != RETAIN_ALL) {
                throw new IllegalArgumentException("retaining sequence number [" + retainingSequenceNumber + "] out of range");
            }
            this.retainingSequenceNumber = retainingSequenceNumber;
            this.source = Objects.requireNonNull(source);
        }

        @Override
        public void readFrom(final StreamInput in) throws IOException {
            super.readFrom(in);
            retainingSequenceNumber = in.readZLong();
            source = in.readString();
        }

        @Override
        public void writeTo(final StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeZLong(retainingSequenceNumber);
            out.writeString(source);
        }

    }

    public static class AddRequest extends AddOrRenewRequest<AddRequest> {

        public AddRequest() {
        }

        public AddRequest(final ShardId shardId, final String id, final long retainingSequenceNumber, final String source) {
            super(shardId, id, retainingSequenceNumber, source);
        }

    }

    public static class RenewRequest extends AddOrRenewRequest<RenewRequest> {

        public RenewRequest() {
        }

        public RenewRequest(final ShardId shardId, final String id, final long retainingSequenceNumber, final String source) {
            super(shardId, id, retainingSequenceNumber, source);
        }

    }

    public static class RemoveRequest extends Request<RemoveRequest> {

        public RemoveRequest() {
        }

        public RemoveRequest(final ShardId shardId, final String id) {
            super(shardId, id);
        }

    }

    public static class Response extends ActionResponse {

    }

}
