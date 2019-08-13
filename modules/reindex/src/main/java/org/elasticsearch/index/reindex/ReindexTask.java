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

package org.elasticsearch.index.reindex;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.support.ContextPreservingActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.OriginSettingClient;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.persistent.PersistentTaskState;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ReindexTask extends AllocatedPersistentTask {

    private static final Logger logger = LogManager.getLogger(ReindexTask.class);

    // TODO: Name
    public static final String NAME = "reindex/job";
    // TODO: Eventually this should be an alias for index versioning
    public static final String REINDEX_INDEX = ".reindex";

    private final NodeClient client;
    private final Client taskClient;
    private final Reindexer reindexer;
    private final TaskId taskId;
    private final NamedXContentRegistry xContentRegistry;
    private final BulkByScrollTask childTask;

    public static class ReindexPersistentTasksExecutor extends PersistentTasksExecutor<ReindexJob> {

        private final ClusterService clusterService;
        private final Client client;
        private final ThreadPool threadPool;
        private final ScriptService scriptService;
        private final ReindexSslConfig reindexSslConfig;
        private final NamedXContentRegistry xContentRegistry;

        public ReindexPersistentTasksExecutor(ClusterService clusterService, Client client, NamedXContentRegistry xContentRegistry,
                                              ThreadPool threadPool, ScriptService scriptService, ReindexSslConfig reindexSslConfig) {
            super(NAME, ThreadPool.Names.GENERIC);
            this.clusterService = clusterService;
            this.client = client;
            this.xContentRegistry = xContentRegistry;
            this.threadPool = threadPool;
            this.scriptService = scriptService;
            this.reindexSslConfig = reindexSslConfig;
        }

        @Override
        protected void nodeOperation(AllocatedPersistentTask task, ReindexJob reindexJob, PersistentTaskState state) {
            ReindexTask reindexTask = (ReindexTask) task;
            reindexTask.execute(reindexJob);
        }

        @Override
        protected AllocatedPersistentTask createTask(long id, String type, String action, TaskId parentTaskId,
                                                     PersistentTasksCustomMetaData.PersistentTask<ReindexJob> taskInProgress,
                                                     Map<String, String> headers) {
            headers.putAll(taskInProgress.getParams().getHeaders());
            Reindexer reindexer = new Reindexer(clusterService, client, threadPool, scriptService, reindexSslConfig);
            return new ReindexTask(id, type, action, parentTaskId, headers, clusterService, xContentRegistry, client, reindexer);
        }
    }

    private ReindexTask(long id, String type, String action, TaskId parentTask, Map<String, String> headers,
                        ClusterService clusterService, NamedXContentRegistry xContentRegistry, Client client, Reindexer reindexer) {
        // TODO: description
        super(id, type, action, "persistent reindex", parentTask, headers);
        this.xContentRegistry = xContentRegistry;
        this.client = (NodeClient) client;
        this.taskClient = new OriginSettingClient(client, ReindexTaskIndexState.REINDEX_ORIGIN);
        this.reindexer = reindexer;
        this.taskId = new TaskId(clusterService.localNode().getId(), id);
        this.childTask = new BulkByScrollTask(id, type, action, getDescription(), parentTask, headers);
    }

    @Override
    public Status getStatus() {
        return childTask.getStatus();
    }

    BulkByScrollTask getChildTask() {
        return childTask;
    }

    private void execute(ReindexJob reindexJob) {
        ThreadContext threadContext = client.threadPool().getThreadContext();
        Supplier<ThreadContext.StoredContext> context = threadContext.newRestorableContext(false);

        GetRequest getRequest = new GetRequest(REINDEX_INDEX).id(getPersistentTaskId());
        taskClient.get(getRequest, ActionListener.map(new ActionListener<>() {
            @Override
            public void onResponse(ReindexRequest reindexRequest) {
                Runnable performReindex = () -> performReindex(reindexJob, reindexRequest, context);
                reindexer.initTask(childTask, reindexRequest, new ActionListener<>() {
                    @Override
                    public void onResponse(Void aVoid) {
                        sendStartedNotification(reindexJob.shouldStoreResult(), performReindex);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        handleError(reindexJob.shouldStoreResult(), e);
                    }
                });
            }

            @Override
            public void onFailure(Exception e) {
                handleError(reindexJob.shouldStoreResult(), e);

            }
        }, this::extractReindexRequest));
    }

    private ReindexRequest extractReindexRequest(GetResponse response) throws IOException {
        BytesReference source = response.getSourceAsBytesRef();
        try (XContentParser parser = XContentHelper.createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, source,
            XContentType.JSON)) {
            ReindexTaskIndexState taskState = ReindexTaskIndexState.fromXContent(parser);
            ReindexRequest reindexRequest = taskState.getReindexRequest();
            reindexRequest.setParentTask(taskId);
            return reindexRequest;
        }
    }

    private void sendStartedNotification(boolean shouldStoreResult, Runnable listener) {
        updatePersistentTaskState(new ReindexJobState(taskId, null, null), new ActionListener<>() {
            @Override
            public void onResponse(PersistentTasksCustomMetaData.PersistentTask<?> persistentTask) {
                listener.run();
            }

            @Override
            public void onFailure(Exception e) {
                logger.info("Failed to update reindex persistent task with ephemeral id", e);
                TaskManager taskManager = getTaskManager();
                assert taskManager != null : "TaskManager should have been set before reindex started";
                markEphemeralTaskFailed(shouldStoreResult, taskManager, e);
            }
        });
    }

    private void performReindex(ReindexJob reindexJob, ReindexRequest reindexRequest, Supplier<ThreadContext.StoredContext> context) {
        TaskManager taskManager = getTaskManager();
        assert taskManager != null : "TaskManager should have been set before reindex started";

        ThreadContext threadContext = client.threadPool().getThreadContext();
        boolean shouldStoreResult = reindexJob.shouldStoreResult();
        // TODO: Eventually we only want to retain security context
        try (ThreadContext.StoredContext ignore = stashWithHeaders(threadContext, reindexJob.getHeaders())) {
            reindexer.execute(childTask, reindexRequest, new ContextPreservingActionListener<>(context, new ActionListener<>() {
                @Override
                public void onResponse(BulkByScrollResponse response) {
                    updatePersistentTaskState(new ReindexJobState(taskId, response, null), new ActionListener<>() {
                        @Override
                        public void onResponse(PersistentTasksCustomMetaData.PersistentTask<?> persistentTask) {
                            if (shouldStoreResult) {
                                taskManager.storeResult(ReindexTask.this, response, new ActionListener<>() {
                                    @Override
                                    public void onResponse(BulkByScrollResponse response) {
                                        markAsCompleted();
                                    }

                                    @Override
                                    public void onFailure(Exception e) {
                                        logger.info("Failed to store task result", e);
                                        markAsFailed(e);
                                    }
                                });
                            } else {
                                markAsCompleted();
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            logger.info("Failed to update task state to success", e);
                            markEphemeralTaskFailed(shouldStoreResult, taskManager, e);
                        }
                    });
                }

                @Override
                public void onFailure(Exception ex) {
                    handleError(shouldStoreResult, ex);
                }
            }));
        }
    }

    private void handleError(boolean shouldStoreResult, Exception ex) {
        TaskManager taskManager = getTaskManager();
        assert taskManager != null : "TaskManager should have been set before reindex started";

        updatePersistentTaskState(new ReindexJobState(taskId, null, wrapException(ex)), new ActionListener<>() {
            @Override
            public void onResponse(PersistentTasksCustomMetaData.PersistentTask<?> persistentTask) {
                markEphemeralTaskFailed(shouldStoreResult, taskManager, ex);
            }

            @Override
            public void onFailure(Exception e) {
                logger.info("Failed to update task state to failed", e);
                ex.addSuppressed(e);
                markEphemeralTaskFailed(shouldStoreResult, taskManager, ex);
            }
        });
    }

    private void markEphemeralTaskFailed(boolean shouldStoreResult, TaskManager taskManager, Exception ex) {
        if (shouldStoreResult) {
            taskManager.storeResult(ReindexTask.this, ex, ActionListener.wrap(() -> markAsFailed(ex)));
        } else {
            markAsFailed(ex);
        }
    }

    private static ElasticsearchException wrapException(Exception ex) {
        if (ex instanceof ElasticsearchException) {
            return (ElasticsearchException) ex;
        } else {
            return new ElasticsearchException(ex);
        }
    }

    // TODO: Copied from ClientHelper in x-pack
    private static ThreadContext.StoredContext stashWithHeaders(ThreadContext threadContext, Map<String, String> headers) {
        final ThreadContext.StoredContext storedContext = threadContext.stashContext();
        if (headers.containsKey(Task.X_OPAQUE_ID)) {
            headers = new HashMap<>(headers);
            // If the X_OPAQUE_ID is present, we should not set it again.
            headers.remove(Task.X_OPAQUE_ID);
        }
        threadContext.copyHeaders(headers.entrySet());
        return storedContext;
    }
}