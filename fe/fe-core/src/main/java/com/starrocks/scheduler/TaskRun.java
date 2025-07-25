// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.scheduler;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.gson.annotations.SerializedName;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.system.SystemTable;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.profile.Timer;
import com.starrocks.common.profile.Tracers;
import com.starrocks.common.util.LogUtil;
import com.starrocks.common.util.PropertyAnalyzer;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.load.loadv2.InsertLoadJob;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.QueryState;
import com.starrocks.qe.StmtExecutor;
import com.starrocks.scheduler.persist.TaskRunStatus;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.SystemVariable;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.optimizer.rule.transformation.materialization.MvUtils;
import com.starrocks.warehouse.Warehouse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.starrocks.common.util.PropertyAnalyzer.PROPERTIES_WAREHOUSE;

public class TaskRun implements Comparable<TaskRun> {

    private static final Logger LOG = LogManager.getLogger(TaskRun.class);

    // NOTE: ALL task run properties should be defined here, and the associated properties configuration below should be
    // added carefully.
    public static final String MV_ID = "mvId";
    public static final String PARTITION_START = "PARTITION_START";
    public static final String PARTITION_END = "PARTITION_END";
    // list partition values to be refreshed
    public static final String PARTITION_VALUES = "PARTITION_VALUES";
    public static final String FORCE = "FORCE";
    public static final String START_TASK_RUN_ID = "START_TASK_RUN_ID";
    // Only used in FE's UT
    public static final String IS_TEST = "__IS_TEST__";

    // TODO: Refactor this for better extensibility later by using a class rather than a map.
    // MV's task run can be generated from the last task run, those properties are not allowed to be copied from one task run
    // to another and must be only set specifically for each run but cannot be extended from the last task run.
    // eg: `FORCE` is only allowed to set in the first task run and cannot be copied into the following task run.
    public static final Set<String> MV_UNCOPYABLE_PROPERTIES = ImmutableSet.of(
            PARTITION_START, PARTITION_END, PARTITION_VALUES, FORCE);
    // If there are many pending mv task runs, we can merge some of them by comparing the properties, those properties that are
    // used to check equality of task runs and we can ignore the other properties.
    // eg:
    // - `FORCE` is used to check equality of task runs because the refresh partitions are different for each task run.
    // - `PROPERTIES_WAREHOUSE`/`START_TASK_RUN_ID` is no need to check equality of task runs because they will not affect
    //  the task run's result.
    public static final Set<String> MV_COMPARABLE_PROPERTIES = ImmutableSet.of(
            MV_ID, PARTITION_START, PARTITION_END, PARTITION_VALUES, FORCE);
    // Properties that can be set in TaskRun which are used to distinguish other noisy properties from users' defined properties.
    // and will ignore other properties in the task run history.
    // This should be only used in the task run history table and should not used for checking task run's real properties
    // because this is not a complete list of task run properties.
    public static final Set<String> RESERVED_HISTORY_TASK_RUN_PROPERTIES = ImmutableSet.of(
            MV_ID, PARTITION_START, PARTITION_END, FORCE, START_TASK_RUN_ID, PARTITION_VALUES, PROPERTIES_WAREHOUSE, IS_TEST);

    public static final int INVALID_TASK_PROGRESS = -1;

    private boolean isKilled = false;

    @SerializedName("taskId")
    private long taskId;

    @SerializedName("taskRunId")
    private final String taskRunId;

    private Map<String, String> properties;

    private final CompletableFuture<Constants.TaskRunState> future;

    private Task task;

    private ConnectContext runCtx;

    private ConnectContext parentRunCtx;

    private TaskRunProcessor processor;

    private TaskRunStatus status;

    private Constants.TaskType type;

    private ExecuteOption executeOption;

    public TaskRun() {
        future = new CompletableFuture<>();
        taskRunId = UUIDUtil.genUUID().toString();
    }

    public long getTaskId() {
        return taskId;
    }

    public void setTaskId(long taskId) {
        this.taskId = taskId;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public CompletableFuture<Constants.TaskRunState> getFuture() {
        return future;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public void setConnectContext(ConnectContext context) {
        this.parentRunCtx = context;
    }

    public TaskRunProcessor getProcessor() {
        return processor;
    }

    public void setProcessor(TaskRunProcessor processor) {
        this.processor = processor;
    }

    public void setType(Constants.TaskType type) {
        this.type = type;
    }

    public Constants.TaskType getType() {
        return this.type;
    }

    public ExecuteOption getExecuteOption() {
        return executeOption;
    }

    public void setExecuteOption(ExecuteOption executeOption) {
        this.executeOption = executeOption;
    }

    public String getTaskRunId() {
        return taskRunId;
    }

    public void kill() {
        isKilled = true;
    }

    public boolean isKilled() {
        return isKilled;
    }

    public Map<String, String> refreshTaskProperties(ConnectContext ctx) {
        Map<String, String> newProperties = Maps.newHashMap();
        if (task.getSource() != Constants.TaskSource.MV) {
            return newProperties;
        }

        try {
            // NOTE: mvId is set in Task's properties when creating
            long mvId = Long.parseLong(properties.get(MV_ID));
            Database database = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(ctx.getDatabase());
            if (database == null) {
                LOG.warn("database {} do not exist when refreshing materialized view:{}", ctx.getDatabase(), mvId);
                return newProperties;
            }

            Table table = GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(database.getId(), mvId);
            if (table == null) {
                LOG.warn("materialized view:{} in database:{} do not exist when refreshing", mvId,
                        ctx.getDatabase());
                return newProperties;
            }
            MaterializedView materializedView = (MaterializedView) table;
            Preconditions.checkState(materializedView != null);
            // Don't copy all table's properties to task's properties:
            // 1. It will cause task run's meta-data to be too large
            // 2. It may pollute the properties of task run.
            newProperties.putAll(materializedView.getSessionProperties());

            Warehouse w = GlobalStateMgr.getCurrentState().getWarehouseMgr().getWarehouse(
                    materializedView.getWarehouseId());
            newProperties.put(PROPERTIES_WAREHOUSE, w.getName());

            // set current warehouse
            ctx.setCurrentWarehouse(w.getName());
        } catch (Exception e) {
            LOG.warn("refresh task properties failed:", e);
        }
        return newProperties;
    }

    @VisibleForTesting
    public ConnectContext buildTaskRunConnectContext() {
        // Create a new ConnectContext for this task run
        final ConnectContext context = new ConnectContext(null);

        if (parentRunCtx != null) {
            context.setParentConnectContext(parentRunCtx);
        }
        context.setGlobalStateMgr(GlobalStateMgr.getCurrentState());
        context.setCurrentCatalog(task.getCatalogName());
        context.setDatabase(task.getDbName());
        context.setQualifiedUser(status.getUser());
        if (status.getUserIdentity() != null) {
            context.setCurrentUserIdentity(status.getUserIdentity());
        } else {
            context.setCurrentUserIdentity(UserIdentity.createAnalyzedUserIdentWithIp(status.getUser(), "%"));
        }
        context.setCurrentRoleIds(context.getCurrentUserIdentity());
        context.getState().reset();
        context.setQueryId(UUID.fromString(status.getQueryId()));
        context.setIsLastStmt(true);
        context.resetSessionVariable();

        // NOTE: Ensure the thread local connect context is always the same with the newest ConnectContext.
        // NOTE: Ensure this thread local is removed after this method to avoid memory leak in JVM.
        context.setThreadLocalInfo();
        return context;
    }

    public Constants.TaskRunState executeTaskRun() throws Exception {
        TaskRunContext taskRunContext = new TaskRunContext();

        // Definition will cause a lot of repeats and cost a lot of metadata memory resources, so
        // ignore it here, and we can get the `definition` from the materialized view's definition too.
        // Use task's definition rather than status's to avoid costing too much metadata memory.
        Preconditions.checkNotNull(task.getDefinition(), "The definition of task run should not null");
        taskRunContext.setDefinition(task.getDefinition());

        // build context for task run
        this.runCtx = buildTaskRunConnectContext();

        Map<String, String> newProperties = refreshTaskProperties(runCtx);
        properties.putAll(newProperties);
        Map<String, String> taskRunContextProperties = Maps.newHashMap();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            // if task contains session properties, we should remove the prefix
            if (key.startsWith(PropertyAnalyzer.PROPERTIES_MATERIALIZED_VIEW_SESSION_PREFIX)) {
                key = key.substring(PropertyAnalyzer.PROPERTIES_MATERIALIZED_VIEW_SESSION_PREFIX.length());
            }
            String value = entry.getValue();
            try {
                runCtx.modifySystemVariable(new SystemVariable(key, new StringLiteral(value)), true);
            } catch (DdlException e) {
                // not session variable
                taskRunContextProperties.put(key, properties.get(key));
            }
        }
        // set warehouse
        String currentWarehouse = properties.get(PropertyAnalyzer.PROPERTIES_WAREHOUSE);
        if (currentWarehouse != null) {
            runCtx.setCurrentWarehouse(currentWarehouse);
            taskRunContextProperties.put(PropertyAnalyzer.PROPERTIES_WAREHOUSE, currentWarehouse);
        }

        LOG.info("[QueryId:{}] [ThreadLocal QueryId: {}] start to execute task run, task_id:{}, " +
                        "taskRunContextProperties:{}", runCtx.getQueryId(),
                ConnectContext.get() == null ? "" : ConnectContext.get().getQueryId(), taskId, taskRunContextProperties);

        // Set the post run action
        taskRunContext.setPostRun(task.getPostRun());
        // If this is the first task run of the job, use its uuid as the job id.
        taskRunContext.setTaskRunId(taskRunId);
        taskRunContext.setCtx(runCtx);
        taskRunContext.setRemoteIp(runCtx.getMysqlChannel().getRemoteHostPortString());
        taskRunContext.setProperties(taskRunContextProperties);
        taskRunContext.setPriority(status.getPriority());
        taskRunContext.setTaskType(type);
        taskRunContext.setStatus(status);
        taskRunContext.setExecuteOption(executeOption);
        taskRunContext.setTaskRun(this);

        // prepare to execute task run, move it here so that we can catch the exception and set the status
        processor.prepare(taskRunContext);

        // process task run
        Constants.TaskRunState taskRunState;
        try (Timer ignored = Tracers.watchScope("TaskRunProcess")) {
            taskRunState = processor.processTaskRun(taskRunContext);
        }

        QueryState queryState = runCtx.getState();
        LOG.info("[QueryId:{}] finished to execute task run, task_id:{}, query_state:{}",
                runCtx.getQueryId(), taskId, queryState);
        if (runCtx.getState().getStateType() == QueryState.MysqlStateType.ERR) {
            status.setErrorMessage(queryState.getErrorMessage());
            int errorCode = -1;
            if (queryState.getErrorCode() != null) {
                errorCode = queryState.getErrorCode().getCode();
            }
            status.setErrorCode(errorCode);
            return Constants.TaskRunState.FAILED;
        }

        // post prosess task run
        try (Timer ignored = Tracers.watchScope("TaskRunPostProcess")) {
            processor.postTaskRun(taskRunContext);
        } catch (Exception e) {
            LOG.warn("Failed to post task run, task_id: {}, task_run_id: {}, error: {}",
                    taskId, taskRunId, e.getMessage());
        }
        return taskRunState;
    }

    public ConnectContext getRunCtx() {
        return runCtx;
    }

    protected void setRunCtx(ConnectContext runCtx) {
        this.runCtx = runCtx;
    }

    public TaskRunStatus getStatus() {
        if (status == null) {
            return null;
        }
        final int progress = getProgress();
        if (progress != INVALID_TASK_PROGRESS) {
            status.setProgress(progress);
        }
        return status;
    }

    private int getProgress() {
        if (status == null) {
            return INVALID_TASK_PROGRESS;
        }

        if (status.getState().isSuccessState()) {
            return 100;
        } else if (status.getState().isFinishState()) {
            return INVALID_TASK_PROGRESS;
        } else {
            if (runCtx == null) {
                return INVALID_TASK_PROGRESS;
            }
            final StmtExecutor executor = runCtx.getExecutor();
            if (executor == null || executor.getCoordinator() == null) {
                return INVALID_TASK_PROGRESS;
            }
            final long jobId = executor.getCoordinator().getLoadJobId();
            if (jobId == -1) {
                return INVALID_TASK_PROGRESS;
            }
            final InsertLoadJob job = (InsertLoadJob) GlobalStateMgr.getCurrentState()
                    .getLoadMgr().getLoadJob(jobId);
            if (job == null) {
                return INVALID_TASK_PROGRESS;
            }
            int progress = job.getProgress();
            // if the progress is 100, we should return 99 to avoid the task run is marked as success
            if (progress == 100) {
                progress = 99;
            }
            return progress;
        }
    }

    public TaskRunStatus initStatus(String queryId, Long createTime) {
        TaskRunStatus status = new TaskRunStatus();
        long created = createTime == null ? System.currentTimeMillis() : createTime;
        status.setQueryId(queryId);
        status.setTaskRunId(taskRunId);
        status.setTaskId(task.getId());
        status.setTaskName(task.getName());
        status.setSource(task.getSource());
        status.setCreateTime(created);
        status.setUser(task.getCreateUser());
        status.setUserIdentity(task.getUserIdentity());
        status.setCatalogName(task.getCatalogName());
        status.setDbName(task.getDbName());
        status.setPostRun(task.getPostRun());
        status.setExpireTime(created + Config.task_runs_ttl_second * 1000L);
        // NOTE: definition will cause a lot of repeats and cost a lot of metadata memory resources,
        // since history task runs has been stored in sr's internal table, we can save it in the
        // task run status.
        if (!Strings.isNullOrEmpty(task.getDefinition())) {
            // Remove line separator and shrink to MAX_FIELD_VARCHAR_LENGTH/4 which is defined in the TaskRunsSystemTable.java
            String query = LogUtil.removeLineSeparator(task.getDefinition());
            status.setDefinition(MvUtils.shrinkToSize(query, SystemTable.MAX_FIELD_VARCHAR_LENGTH / 4));
        }
        status.getMvTaskRunExtraMessage().setExecuteOption(this.executeOption);

        LOG.info("init task status, task:{}, query_id:{}, create_time:{}", task.getName(), queryId, status.getCreateTime());
        this.status = status;
        return status;
    }

    @Override
    public int compareTo(@NotNull TaskRun taskRun) {
        int ret = comparePriority(this.status, taskRun.status);
        if (ret != 0) {
            return ret;
        }
        return taskRunId.compareTo(taskRun.taskRunId);
    }

    private int comparePriority(TaskRunStatus t0, TaskRunStatus t1) {
        if (t0 == null || t1 == null) {
            // prefer this
            return 0;
        }
        // if priority is different, return the higher priority
        if (t0.getPriority() != t1.getPriority()) {
            return Integer.compare(t1.getPriority(), t0.getPriority());
        } else {
            // if priority is the same, return the older task
            return Long.compare(t0.getCreateTime(), t1.getCreateTime());
        }
    }

    /**
     * Check the taskRun is equal task to the given taskRun which means they have the same taskRunId and the same task.
     */
    public boolean isEqualTask(TaskRun o) {
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }
        if (task.getDefinition() == null) {
            return false;
        }
        return this.taskId == o.getTaskId() &&
                this.task.getDefinition().equals(o.getTask().getDefinition());
    }

    /**
     * TaskRun is equal if they have the same taskRunId and the same task.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TaskRun taskRun = (TaskRun) o;
        return this.taskRunId.equals(taskRun.getTaskRunId()) && isEqualTask(taskRun);
    }

    @Override
    public int hashCode() {
        return Objects.hash(task);
    }

    @Override
    public String toString() {
        return "TaskRun{" +
                "taskId=" + taskId +
                ", type=" + type +
                ", uuid=" + taskRunId +
                ", task_state=" + (status != null ? status.getState() : "") +
                ", properties=" + properties +
                ", extra_message =" + (status != null ? status.getExtraMessage() : "") +
                '}';
    }
}
