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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/qe/MasterOpExecutor.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.qe;

import com.google.common.base.Preconditions;
import com.starrocks.analysis.RedirectStatus;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.common.Pair;
import com.starrocks.common.util.AuditStatisticsUtil;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.mysql.MysqlChannel;
import com.starrocks.qe.QueryState.MysqlStateType;
import com.starrocks.rpc.ThriftConnectionPool;
import com.starrocks.rpc.ThriftRPCRequestExecutor;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.GracefulExitFlag;
import com.starrocks.sql.analyzer.AstToSQLBuilder;
import com.starrocks.sql.ast.SetListItem;
import com.starrocks.sql.ast.SetStmt;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.SystemVariable;
import com.starrocks.sql.ast.txn.BeginStmt;
import com.starrocks.sql.ast.txn.CommitStmt;
import com.starrocks.sql.ast.txn.RollbackStmt;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.TAuditStatistics;
import com.starrocks.thrift.TMasterOpRequest;
import com.starrocks.thrift.TMasterOpResult;
import com.starrocks.thrift.TNetworkAddress;
import com.starrocks.thrift.TQueryOptions;
import com.starrocks.thrift.TUserRoles;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class LeaderOpExecutor {
    private static final Logger LOG = LogManager.getLogger(LeaderOpExecutor.class);
    public static final int MAX_FORWARD_TIMES = 30;

    private final OriginStatement originStmt;
    private StatementBase parsedStmt;
    private final ConnectContext ctx;
    private final boolean isInternalStmt;
    private TMasterOpResult result;

    private int waitTimeoutMs;
    // the total time of thrift connectTime add readTime and writeTime
    private int thriftTimeoutMs;
    private final Pair<String, Integer> ipAndPort;

    public LeaderOpExecutor(OriginStatement originStmt, ConnectContext ctx, RedirectStatus status) {
        this(null, originStmt, ctx, status, false);
    }

    public LeaderOpExecutor(StatementBase parsedStmt, OriginStatement originStmt,
                            ConnectContext ctx, RedirectStatus status, boolean isInternalStmt) {
        this(GlobalStateMgr.getCurrentState().getNodeMgr().getLeaderIpAndRpcPort(), parsedStmt, originStmt, ctx,
                status, isInternalStmt);
    }

    public LeaderOpExecutor(Pair<String, Integer> ipAndPort, StatementBase parsedStmt, OriginStatement originStmt,
                            ConnectContext ctx, RedirectStatus status, boolean isInternalStmt) {
        this.ipAndPort = ipAndPort;
        this.originStmt = originStmt;
        this.ctx = ctx;
        if (status.isNeedToWaitJournalSync()) {
            this.waitTimeoutMs = ctx.getExecTimeout() * 1000;
        } else {
            this.waitTimeoutMs = 0;
        }
        // set thriftTimeoutMs to exec timeout + thrift_rpc_timeout_ms
        // so that we can return an execution timeout instead of a network timeout
        this.thriftTimeoutMs = ctx.getExecTimeout() * 1000 + Config.thrift_rpc_timeout_ms;
        if (this.thriftTimeoutMs < 0) {
            this.thriftTimeoutMs = ctx.getExecTimeout() * 1000;
        }
        this.parsedStmt = parsedStmt;
        this.isInternalStmt = isInternalStmt;
    }

    public void execute() throws Exception {
        forward();
        if (!GracefulExitFlag.isGracefulExit() && !GlobalStateMgr.getCurrentState().isLeader()) {
            LOG.info("forwarding to leader get result max journal id: {}", result.maxJournalId);
            ctx.getGlobalStateMgr().getJournalObservable().waitOn(result.maxJournalId, waitTimeoutMs);
        }

        if (result.state != null) {
            MysqlStateType state = MysqlStateType.fromString(result.state);
            if (state != null) {
                ctx.getState().setStateType(state);
                if (result.isSetErrorMsg()) {
                    ctx.getState().setMsg(result.getErrorMsg());
                }
                if (state == MysqlStateType.EOF || state == MysqlStateType.OK) {
                    afterForward();
                }
            }
        }

        if (result.isSetResource_group_name()) {
            ctx.getAuditEventBuilder().setResourceGroup(result.getResource_group_name());
        }
        if (result.isSetAudit_statistics()) {
            TAuditStatistics tAuditStatistics = result.getAudit_statistics();
            if (ctx.getExecutor() != null) {
                ctx.getExecutor().setQueryStatistics(AuditStatisticsUtil.toProtobuf(tAuditStatistics));
            }
        }

        // Put the result of leader execution into the connectContext of the current follower
        // to mark the transaction being executed in the current connection
        if (parsedStmt instanceof BeginStmt || parsedStmt instanceof CommitStmt || parsedStmt instanceof RollbackStmt) {
            if (result.isSetTxn_id()) {
                ctx.setTxnId(result.getTxn_id());
            }
        }
    }

    private void afterForward() throws DdlException {
        if (parsedStmt != null && parsedStmt instanceof SetStmt) {
            try {
                /*
                 * This method is only called after a set statement is forward to the leader.
                 * In this case, the follower should change this session variable as well.
                 */
                SetStmt stmt = (SetStmt) parsedStmt;
                for (SetListItem var : stmt.getSetListItems()) {
                    if (var instanceof SystemVariable) {
                        GlobalStateMgr.getCurrentState().getVariableMgr().setSystemVariable(
                                ctx.getSessionVariable(), (SystemVariable) var, true);
                    }
                }
            } catch (DdlException e) {
                LOG.warn("set session variables after forward failed", e);
                // set remote result to null, so that mysql protocol will show the error message
                result = null;
                throw new DdlException("Global level variables are set successfully, " +
                        "but session level variables are set failed with error: " + e.getMessage() + ". " +
                        "Please check if the version of fe currently connected is the same as the version of master, " +
                        "or re-establish the connection and you will see the new variables");
            }
        }
    }

    // Send request to Leader
    private void forward() throws Exception {
        int forwardTimes = ctx.getForwardTimes() + 1;
        if (forwardTimes > 1) {
            LOG.info("forward multi times: {}", forwardTimes);
        }
        if (forwardTimes > MAX_FORWARD_TIMES) {
            LOG.warn("too many forward times, max allowed forward time is {}", MAX_FORWARD_TIMES);
            throw ErrorReportException.report(ErrorCode.ERR_FORWARD_TOO_MANY_TIMES, forwardTimes);
        }

        TNetworkAddress thriftAddress = new TNetworkAddress(ipAndPort.first, ipAndPort.second);
        TMasterOpRequest params = createTMasterOpRequest(ctx, forwardTimes);
        LOG.info("Forward statement {} to Leader {}", ctx.getStmtId(), thriftAddress);

        result = ThriftRPCRequestExecutor.call(
                ThriftConnectionPool.frontendPool,
                thriftAddress,
                thriftTimeoutMs,
                client -> client.forward(params));
    }

    public TMasterOpResult getResult() {
        return result;
    }

    public ByteBuffer getOutputPacket() {
        if (result == null) {
            return null;
        }
        return result.packet;
    }

    public ShowResultSet getProxyResultSet() {
        if (result == null) {
            return null;
        }
        if (result.isSetResultSet()) {
            return new ShowResultSet(result.resultSet);
        } else {
            return null;
        }
    }

    /**
     * if a query statement is forwarded to the leader, or if a show statement is automatically rewrote,
     * the result of the query will be returned in thrift body and should write into mysql channel.
     **/
    public boolean sendResultToChannel(MysqlChannel channel) throws IOException {
        if (!result.isSetChannelBufferList() || result.channelBufferList.isEmpty()) {
            return false;
        }
        for (ByteBuffer byteBuffer : result.channelBufferList) {
            channel.sendOnePacket(byteBuffer);
        }
        return true;
    }

    public void setResult(TMasterOpResult result) {
        this.result = result;
    }

    public TMasterOpRequest createTMasterOpRequest(ConnectContext ctx, int forwardTimes) {
        TMasterOpRequest params = new TMasterOpRequest();
        params.setCluster(SystemInfoService.DEFAULT_CLUSTER);
        params.setSql(originStmt.originStmt);
        params.setStmtIdx(originStmt.idx);
        params.setUser(ctx.getQualifiedUser());
        params.setCatalog(ctx.getCurrentCatalog());
        params.setDb(ctx.getDatabase());
        params.setSqlMode(ctx.getSessionVariable().getSqlMode());
        params.setUser_ip(ctx.getRemoteIP());
        params.setTime_zone(ctx.getSessionVariable().getTimeZone());
        params.setStmt_id(ctx.getStmtId());
        params.setEnableStrictMode(ctx.getSessionVariable().getEnableInsertStrict());
        params.setCurrent_user_ident(ctx.getCurrentUserIdentity().toThrift());
        params.setForward_times(forwardTimes);
        params.setSession_id(ctx.getSessionId().toString());
        params.setConnectionId(ctx.getConnectionId());

        TUserRoles currentRoles = new TUserRoles();
        Preconditions.checkState(ctx.getCurrentRoleIds() != null);
        currentRoles.setRole_id_list(new ArrayList<>(ctx.getCurrentRoleIds()));
        params.setUser_roles(currentRoles);

        params.setIsLastStmt(ctx.getIsLastStmt());

        TQueryOptions queryOptions = new TQueryOptions();
        queryOptions.setMem_limit(ctx.getSessionVariable().getMaxExecMemByte());
        queryOptions.setQuery_timeout(ctx.getSessionVariable().getQueryTimeoutS());
        queryOptions.setLoad_mem_limit(ctx.getSessionVariable().getLoadMemLimit());
        params.setQuery_options(queryOptions);

        params.setQueryId(UUIDUtil.toTUniqueId(ctx.getQueryId()));
        // forward all session variables
        SetStmt setStmt = ctx.getModifiedSessionVariables();
        if (setStmt != null) {
            params.setModified_variables_sql(AstToSQLBuilder.toSQL(setStmt));
        }

        params.setTxn_id(ctx.getTxnId());

        params.setWarehouse_id(ctx.getCurrentWarehouseId());

        params.setIsInternalStmt(isInternalStmt);
        return params;
    }
}

