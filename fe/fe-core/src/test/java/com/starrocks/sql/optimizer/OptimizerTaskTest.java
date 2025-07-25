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

package com.starrocks.sql.optimizer;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.BinaryType;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FunctionName;
import com.starrocks.analysis.JoinOperator;
import com.starrocks.catalog.AggregateFunction;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.ColumnId;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.HashDistributionInfo;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.common.FeConstants;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.base.DistributionSpec;
import com.starrocks.sql.optimizer.base.HashDistributionDesc;
import com.starrocks.sql.optimizer.base.Ordering;
import com.starrocks.sql.optimizer.dump.MockDumpInfo;
import com.starrocks.sql.optimizer.operator.AggType;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.Projection;
import com.starrocks.sql.optimizer.operator.logical.LogicalAggregationOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalFilterOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalJoinOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalLimitOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalProjectOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalTopNOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalDistributionOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalHashAggregateOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.scalar.ArrayOperator;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rule.transformation.SplitTwoPhaseAggRule;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class OptimizerTaskTest {
    private ColumnRefFactory columnRefFactory;
    private ColumnRefOperator column1;
    private ColumnRefOperator column2;
    private ColumnRefOperator column3;
    private ColumnRefOperator column4;
    private ColumnRefOperator column5;
    private ColumnRefOperator column6;

    private CallOperator call;
    private ConnectContext ctx;

    @BeforeEach
    public void init() throws Exception {
        ctx = UtFrameUtils.createDefaultCtx();
        ctx.getSessionVariable().setMaxTransformReorderJoins(8);
        ctx.getSessionVariable().setEnableReplicationJoin(false);
        ctx.getSessionVariable().setJoinImplementationMode("auto");
        ctx.getSessionVariable().setCboPushDownAggregateMode(-1);
        ctx.setDumpInfo(new MockDumpInfo());
        call = new CallOperator(FunctionSet.SUM, Type.BIGINT, Lists.newArrayList(ConstantOperator.createBigint(1)));
        new Expectations(call) {
            {
                call.getUsedColumns();
                result = new ColumnRefSet();
                minTimes = 0;

                call.getFunction();
                minTimes = 0;
                result = AggregateFunction.createBuiltin(FunctionSet.SUM,
                        Lists.<Type>newArrayList(Type.INT), Type.BIGINT, Type.BIGINT, false, true, false);
            }
        };

        columnRefFactory = new ColumnRefFactory();
        column1 = columnRefFactory.create("t1", ScalarType.INT, true);
        column2 = columnRefFactory.create("t2", ScalarType.INT, true);
        column3 = columnRefFactory.create("t3", ScalarType.INT, true);
        column4 = columnRefFactory.create("t4", ScalarType.INT, true);
        column5 = columnRefFactory.create("t5", ScalarType.INT, true);
        column6 = columnRefFactory.create("t6", ScalarType.INT, true);

        FeConstants.enablePruneEmptyOutputScan = false;
    }

    @AfterEach
    public void tearDown() {
        ctx.getSessionVariable().setJoinImplementationMode("auto");
    }

    @Test
    public void testTaskScheduler(@Mocked OlapTable olapTable1,
                                  @Mocked OlapTable olapTable2) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable2.getId();
                result = 1;
                minTimes = 0;
            }
        };

        Map<ColumnRefOperator, Column> scan1ColumnMap = Maps.newHashMap();
        scan1ColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scan1ColumnMap.put(column2, new Column("t2", ScalarType.INT, true));

        OptExpression logicOperatorTree = OptExpression.create(new LogicalJoinOperator(),
                OptExpression.create(new LogicalOlapScanOperator(olapTable1,
                        scan1ColumnMap, Maps.newHashMap(), null, -1, null)),
                OptExpression.create(new LogicalOlapScanOperator(olapTable2,
                        scan1ColumnMap, Maps.newHashMap(), null, -1, null)));

        new Expectations() {
            {
                olapTable1.getBaseSchema();
                result = new ArrayList<>(scan1ColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable2.getBaseSchema();
                result = new ArrayList<>(scan1ColumnMap.values());
                minTimes = 0;
            }
        };

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        optimizer.optimize(logicOperatorTree, new ColumnRefSet());
        Memo memo = optimizer.getContext().getMemo();
        assertEquals(3, memo.getGroups().size());
        assertEquals(8, memo.getGroupExpressions().size());

        assertEquals(memo.getGroups().get(0).getLogicalExpressions().size(), 1);
        assertEquals(memo.getGroups().get(0).getPhysicalExpressions().size(), 1);
        assertEquals(memo.getGroups().get(0).getLogicalExpressions().
                get(0).getOp().getOpType(), OperatorType.LOGICAL_OLAP_SCAN);
        assertEquals(memo.getGroups().get(0).getPhysicalExpressions().
                get(0).getOp().getOpType(), OperatorType.PHYSICAL_OLAP_SCAN);

        assertEquals(memo.getGroups().get(1).getLogicalExpressions().size(), 1);
        assertEquals(memo.getGroups().get(1).getPhysicalExpressions().size(), 1);

        assertEquals(memo.getGroups().get(1).getLogicalExpressions().
                get(0).getOp().getOpType(), OperatorType.LOGICAL_OLAP_SCAN);
        assertEquals(memo.getGroups().get(1).getPhysicalExpressions().
                get(0).getOp().getOpType(), OperatorType.PHYSICAL_OLAP_SCAN);

        assertEquals(memo.getGroups().get(2).getLogicalExpressions().size(), 2);
        assertEquals(memo.getGroups().get(2).getPhysicalExpressions().size(), 2);

        assertEquals(memo.getGroups().get(2).getLogicalExpressions().
                get(0).getOp().getOpType(), OperatorType.LOGICAL_JOIN);
        assertEquals(memo.getGroups().get(2).getPhysicalExpressions().
                get(0).getOp().getOpType(), OperatorType.PHYSICAL_NESTLOOP_JOIN);

        MemoStatusChecker checker = new MemoStatusChecker(memo, 2, new ColumnRefSet(Lists.newArrayList(column1)));
        checker.checkStatus();
    }

    @Test
    public void testTwoJoin(@Mocked OlapTable olapTable1,
                            @Mocked OlapTable olapTable2,
                            @Mocked OlapTable olapTable3) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable2.getId();
                result = 1;
                minTimes = 0;

                olapTable3.getId();
                result = 2;
                minTimes = 0;
            }
        };

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));

        OptExpression bottomJoin = OptExpression.create(new LogicalJoinOperator(),
                OptExpression.create(new LogicalOlapScanOperator(olapTable1,
                        scanColumnMap, Maps.newHashMap(), null, -1, null)),
                OptExpression.create(new LogicalOlapScanOperator(olapTable2,
                        scanColumnMap, Maps.newHashMap(), null, -1, null)));

        OptExpression topJoin = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin,
                new OptExpression(new LogicalOlapScanOperator(olapTable3,
                        scanColumnMap, Maps.newHashMap(), null, -1, null)));

        new Expectations() {
            {
                olapTable1.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable2.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable3.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }
        };

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        optimizer.optimize(topJoin, new ColumnRefSet());

        Memo memo = optimizer.getContext().getMemo();

        assertEquals(memo.getGroups().get(0).getLogicalExpressions().size(), 1);
        assertEquals(memo.getGroups().get(0).getPhysicalExpressions().size(), 1);

        assertEquals(memo.getGroups().get(0).getLogicalExpressions().
                get(0).getOp().getOpType(), OperatorType.LOGICAL_OLAP_SCAN);
        assertEquals(memo.getGroups().get(0).getPhysicalExpressions().
                get(0).getOp().getOpType(), OperatorType.PHYSICAL_OLAP_SCAN);

        assertEquals(memo.getGroups().get(1).getLogicalExpressions().size(), 1);
        assertEquals(memo.getGroups().get(1).getPhysicalExpressions().size(), 1);

        assertEquals(memo.getGroups().get(1).getLogicalExpressions().
                get(0).getOp().getOpType(), OperatorType.LOGICAL_OLAP_SCAN);
        assertEquals(memo.getGroups().get(1).getPhysicalExpressions().
                get(0).getOp().getOpType(), OperatorType.PHYSICAL_OLAP_SCAN);

        assertEquals(2, memo.getGroups().get(2).getLogicalExpressions().size());
        assertEquals(2, memo.getGroups().get(2).getPhysicalExpressions().size());

        assertEquals(memo.getGroups().get(2).getLogicalExpressions().
                get(0).getOp().getOpType(), OperatorType.LOGICAL_JOIN);
        assertEquals(memo.getGroups().get(2).getPhysicalExpressions().
                get(0).getOp().getOpType(), OperatorType.PHYSICAL_NESTLOOP_JOIN);

        assertEquals(memo.getGroups().get(3).getLogicalExpressions().size(), 1);
        assertEquals(memo.getGroups().get(3).getPhysicalExpressions().size(), 1);

        assertEquals(memo.getGroups().get(3).getLogicalExpressions().
                get(0).getOp().getOpType(), OperatorType.LOGICAL_OLAP_SCAN);
        assertEquals(memo.getGroups().get(3).getPhysicalExpressions().
                get(0).getOp().getOpType(), OperatorType.PHYSICAL_OLAP_SCAN);

        assertEquals(memo.getGroups().get(4).getLogicalExpressions().
                get(0).getOp().getOpType(), OperatorType.LOGICAL_JOIN);
        assertEquals(memo.getGroups().get(4).getPhysicalExpressions().
                get(0).getOp().getOpType(), OperatorType.PHYSICAL_NESTLOOP_JOIN);
    }

    @Test
    public void testThreeJoin(@Mocked OlapTable olapTable1,
                              @Mocked OlapTable olapTable2,
                              @Mocked OlapTable olapTable3,
                              @Mocked OlapTable olapTable4) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable2.getId();
                result = 1;
                minTimes = 0;

                olapTable3.getId();
                result = 2;
                minTimes = 0;

                olapTable4.getId();
                result = 3;
                minTimes = 0;
            }
        };

        Map<ColumnRefOperator, Column> scan1ColumnMap = Maps.newHashMap();
        scan1ColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        Map<ColumnRefOperator, Column> scan2ColumnMap = Maps.newHashMap();
        scan2ColumnMap.put(column2, new Column("t2", ScalarType.INT, true));
        Map<ColumnRefOperator, Column> scan3ColumnMap = Maps.newHashMap();
        scan3ColumnMap.put(column3, new Column("t3", ScalarType.INT, true));
        Map<ColumnRefOperator, Column> scan4ColumnMap = Maps.newHashMap();
        scan4ColumnMap.put(column4, new Column("t4", ScalarType.INT, true));

        new Expectations() {
            {
                olapTable1.getBaseSchema();
                result = new ArrayList<>(scan1ColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable2.getBaseSchema();
                result = new ArrayList<>(scan2ColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable3.getBaseSchema();
                result = new ArrayList<>(scan3ColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable4.getBaseSchema();
                result = new ArrayList<>(scan4ColumnMap.values());
                minTimes = 0;
            }
        };

        OptExpression bottomJoin = OptExpression.create(new LogicalJoinOperator(),
                OptExpression.create(new LogicalOlapScanOperator(olapTable1,
                        scan1ColumnMap, Maps.newHashMap(), null, -1, null)),
                OptExpression.create(new LogicalOlapScanOperator(olapTable2,
                        scan2ColumnMap, Maps.newHashMap(), null, -1, null)));

        OptExpression bottomJoin2 = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin,
                new OptExpression(new LogicalOlapScanOperator(olapTable3,
                        scan3ColumnMap, Maps.newHashMap(), null, -1, null)));

        OptExpression topJoin = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin2,
                new OptExpression(
                        new LogicalOlapScanOperator(olapTable4,
                                scan4ColumnMap, Maps.newHashMap(), null,
                                -1, null)));

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        optimizer.optimize(topJoin, new ColumnRefSet());
    }

    @Test
    public void testFourJoin(@Mocked OlapTable olapTable1,
                             @Mocked OlapTable olapTable2,
                             @Mocked OlapTable olapTable3,
                             @Mocked OlapTable olapTable4,
                             @Mocked OlapTable olapTable5) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable2.getId();
                result = 1;
                minTimes = 0;

                olapTable3.getId();
                result = 2;
                minTimes = 0;

                olapTable4.getId();
                result = 3;
                minTimes = 0;

                olapTable5.getId();
                result = 4;
                minTimes = 0;
            }
        };

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));

        OptExpression bottomJoin = OptExpression.create(new LogicalJoinOperator(),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable2, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        OptExpression bottomJoin2 = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin,
                new OptExpression(
                        new LogicalOlapScanOperator(olapTable3, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        OptExpression bottomJoin3 = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin2,
                new OptExpression(
                        new LogicalOlapScanOperator(olapTable5, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        OptExpression topJoin = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin3,
                new OptExpression(
                        new LogicalOlapScanOperator(olapTable4, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        new Expectations() {
            {
                olapTable1.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable2.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable3.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable4.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable5.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }
        };

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        optimizer.optimize(topJoin, new ColumnRefSet());
    }

    @Test
    public void testSevenJoin(@Mocked OlapTable olapTable1,
                              @Mocked OlapTable olapTable2,
                              @Mocked OlapTable olapTable3,
                              @Mocked OlapTable olapTable4,
                              @Mocked OlapTable olapTable5,
                              @Mocked OlapTable olapTable6,
                              @Mocked OlapTable olapTable7,
                              @Mocked OlapTable olapTable8) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable2.getId();
                result = 1;
                minTimes = 0;

                olapTable3.getId();
                result = 2;
                minTimes = 0;

                olapTable4.getId();
                result = 3;
                minTimes = 0;

                olapTable5.getId();
                result = 4;
                minTimes = 0;

                olapTable6.getId();
                result = 5;
                minTimes = 0;

                olapTable7.getId();
                result = 6;
                minTimes = 0;

                olapTable8.getId();
                result = 7;
                minTimes = 0;
            }
        };

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));

        OptExpression bottomJoin = OptExpression.create(new LogicalJoinOperator(),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable2, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        OptExpression bottomJoin2 = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin,
                new OptExpression(
                        new LogicalOlapScanOperator(olapTable3, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        OptExpression bottomJoin3 = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin2,
                new OptExpression(
                        new LogicalOlapScanOperator(olapTable5, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        OptExpression bottomJoin4 = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin3,
                new OptExpression(
                        new LogicalOlapScanOperator(olapTable6, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        OptExpression bottomJoin5 = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin4,
                new OptExpression(
                        new LogicalOlapScanOperator(olapTable7, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        OptExpression bottomJoin6 = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin5,
                new OptExpression(
                        new LogicalOlapScanOperator(olapTable8, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        OptExpression topJoin = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin6,
                new OptExpression(
                        new LogicalOlapScanOperator(olapTable4, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        new Expectations() {
            {
                olapTable1.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable2.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable3.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable4.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable5.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable6.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable7.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }

            {
                olapTable8.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }
        };

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        optimizer.optimize(topJoin, new ColumnRefSet());
    }

    @Test
    public void testDeriveOutputColumns(@Mocked OlapTable olapTable1,
                                        @Mocked OlapTable olapTable2) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable2.getId();
                result = 1;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> outputColumns1 = Lists.newArrayList();
        outputColumns1.add(column1);
        outputColumns1.add(column2);
        Map<ColumnRefOperator, Column> scanColumnMap1 = Maps.newHashMap();
        scanColumnMap1.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap1.put(column2, new Column("t2", ScalarType.INT, true));

        List<ColumnRefOperator> outputColumns2 = Lists.newArrayList();
        outputColumns2.add(column3);
        outputColumns2.add(column4);

        Map<ColumnRefOperator, Column> scanColumnMap2 = Maps.newHashMap();
        scanColumnMap2.put(column3, new Column("t3", ScalarType.INT, true));
        scanColumnMap2.put(column4, new Column("t4", ScalarType.INT, true));

        OptExpression logicOperatorTree = OptExpression.create(new LogicalJoinOperator(),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap1, Maps.newHashMap(), null,
                                -1, null)),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable2, scanColumnMap2, Maps.newHashMap(), null,
                                -1, null)));

        List<ColumnRefOperator> outputColumns = Lists.newArrayList();
        outputColumns.addAll(outputColumns1);
        outputColumns.addAll(outputColumns2);
        ColumnRefSet outputColumnsSet = new ColumnRefSet(outputColumns);

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        optimizer.optimize(logicOperatorTree, outputColumnsSet);

        Memo memo = optimizer.getContext().getMemo();

        MemoStatusChecker checker = new MemoStatusChecker(memo, 2, outputColumnsSet);
        checker.checkStatus();
    }

    @Test
    public void testExtractBestPlanForThreeTable(@Mocked OlapTable olapTable1,
                                                 @Mocked OlapTable olapTable2,
                                                 @Mocked OlapTable olapTable3) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable2.getId();
                result = 1;
                minTimes = 0;

                olapTable3.getId();
                result = 2;
                minTimes = 0;
            }
        };

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));

        OptExpression bottomJoin = OptExpression.create(new LogicalJoinOperator(),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable2, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        OptExpression topJoin = OptExpression.create(new LogicalJoinOperator(),
                bottomJoin,
                new OptExpression(
                        new LogicalOlapScanOperator(olapTable3, scanColumnMap,
                                Maps.newHashMap(), null, -1, null)));

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(topJoin,
                new ColumnRefSet(List.of(column1)));

        assertEquals(physicalTree.getOp().getOpType(), OperatorType.PHYSICAL_NESTLOOP_JOIN);
        assertEquals(physicalTree.inputAt(0).getOp().getOpType(), OperatorType.PHYSICAL_NESTLOOP_JOIN);
        assertEquals(physicalTree.inputAt(1).getOp().getOpType(), OperatorType.PHYSICAL_DISTRIBUTION);
    }

    @Test
    public void testTopDownRewrite(@Mocked OlapTable olapTable1) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;
            }
        };

        ColumnRefOperator column1 = columnRefFactory.create("column1", ScalarType.DATE, false);
        Map<ColumnRefOperator, Column> scanColumnMap = com.google.common.collect.Maps.newHashMap();
        scanColumnMap.put(column1, new Column("column1", Type.DATE, false));

        OptExpression expression = OptExpression.create(LogicalLimitOperator.init(1),
                OptExpression.create(new LogicalOlapScanOperator(olapTable1,
                        scanColumnMap, Maps.newHashMap(), null, -1, null)));

        new Expectations() {
            {
                olapTable1.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }
        };

        ctx.getSessionVariable().setOptimizerExecuteTimeout(10000);
        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(expression, new ColumnRefSet());

        Operator root = physicalTree.getOp();
        assertEquals(root.getOpType(), OperatorType.PHYSICAL_LIMIT);
        ctx.getSessionVariable().setOptimizerExecuteTimeout(3000);
    }

    @Test
    public void testPruneOlapScanColumnsRule(@Mocked OlapTable olapTable1) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> outputColumns1 = Lists.newArrayList();
        outputColumns1.add(column4);

        Map<ColumnRefOperator, ScalarOperator> columnRefMap1 = Maps.newHashMap();
        columnRefMap1.put(column4, column1);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));
        scanColumnMap.put(column3, new Column("t3", ScalarType.INT, true));

        OptExpression expression = OptExpression.create(new LogicalProjectOperator(columnRefMap1),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null,
                                -1, null)));

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(expression,
                new ColumnRefSet(outputColumns1));

        assertEquals(OperatorType.PHYSICAL_OLAP_SCAN, physicalTree.getOp().getOpType());
        PhysicalOlapScanOperator physicalOlapScan = (PhysicalOlapScanOperator) physicalTree.getOp();
        assertEquals(physicalOlapScan.getProjection().getOutputColumns(), Lists.newArrayList(column4));

        assertEquals(optimizer.getContext().getMemo().getRootGroup().
                getLogicalProperty().getOutputColumns(), new ColumnRefSet(outputColumns1));
    }

    @Test
    public void testPruneOlapScanColumnsRuleWithConstant(@Mocked OlapTable olapTable1) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> outputColumns1 = Lists.newArrayList();
        outputColumns1.add(column4);

        Map<ColumnRefOperator, ScalarOperator> columnRefMap1 = Maps.newHashMap();
        columnRefMap1.put(column4, ConstantOperator.createInt(1));

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));
        scanColumnMap.put(column3, new Column("t3", ScalarType.INT, true));

        OptExpression expression = OptExpression.create(new LogicalProjectOperator(columnRefMap1),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null,
                                -1, null)));

        new Expectations() {
            {
                olapTable1.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }
        };

        Optimizer optimizer = OptimizerFactory.create(
                OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(expression,
                new ColumnRefSet(outputColumns1));

        assertNotNull(physicalTree.getOp().getProjection());
        assertEquals(physicalTree.getOp().getOpType(), OperatorType.PHYSICAL_OLAP_SCAN);
        PhysicalOlapScanOperator physicalOlapScan = (PhysicalOlapScanOperator) physicalTree.getOp();
        assertEquals(physicalOlapScan.getProjection().getOutputColumns(), Lists.newArrayList(column4));

        assertEquals(optimizer.getContext().getMemo().getRootGroup().
                getLogicalProperty().getOutputColumns(), new ColumnRefSet(outputColumns1));
    }

    @Test
    public void testPruneAggregateColumnsRule(@Mocked OlapTable olapTable1) {
        ctx.getSessionVariable().setNewPlanerAggStage(2);
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getRowCount();
                result = 10000;
                minTimes = 0;

                olapTable1.getType();
                result = Table.TableType.OLAP;
                minTimes = 0;
            }
        };

        CallOperator call =
                new CallOperator(FunctionSet.SUM, Type.BIGINT, Lists.newArrayList(ConstantOperator.createBigint(1)));
        new Expectations(call) {
            {
                call.getFunction();
                minTimes = 0;
                result = AggregateFunction.createBuiltin(FunctionSet.SUM,
                        Lists.<Type>newArrayList(Type.INT), Type.BIGINT, Type.BIGINT, false, true, false);
            }
        };

        Map<ColumnRefOperator, ScalarOperator> projectColumnMap = Maps.newHashMap();
        projectColumnMap.put(column2, column2);

        List<ColumnRefOperator> scanColumns = Lists.newArrayList();
        scanColumns.add(column1);
        scanColumns.add(column2);
        scanColumns.add(column3);
        scanColumns.add(column4);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));
        scanColumnMap.put(column3, new Column("t3", ScalarType.INT, true));
        scanColumnMap.put(column4, new Column("t4", ScalarType.INT, true));

        Map<ColumnRefOperator, CallOperator> map = Maps.newHashMap();
        map.put(column2, call);
        LogicalAggregationOperator aggregationOperator =
                new LogicalAggregationOperator(AggType.GLOBAL, Lists.newArrayList(column3), map);

        OptExpression expression = OptExpression.create(new LogicalProjectOperator(projectColumnMap),
                OptExpression.create(aggregationOperator,
                        OptExpression.create(
                                new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(),
                                        null, -1,
                                        null))));

        ColumnRefSet outputColumns = new ColumnRefSet(column2.getId());

        try {
            Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
            optimizer.optimize(expression, outputColumns);
            fail("invalid plan. can not optimize success");
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("Type check failed. the type of arg 2: t2 in " +
                    "expr '2: t2' is defined as INT, but the actual type is BIGINT"), e.getMessage());
        }

        try {
            ConnectContext tmp = UtFrameUtils.createDefaultCtx();
            tmp.getSessionVariable().setMaxTransformReorderJoins(8);
            tmp.getSessionVariable().setEnableReplicationJoin(false);
            tmp.getSessionVariable().setJoinImplementationMode("auto");
            tmp.getSessionVariable().setEnablePlanValidation(false);
            tmp.setDumpInfo(new MockDumpInfo());

            Optimizer optimizer = OptimizerFactory.create(
                    OptimizerFactory.mockContext(ctx, columnRefFactory));
            OptExpression expression1 = optimizer.optimize(expression, outputColumns);
            Map<ColumnRefOperator, CallOperator> aggs =
                    ((PhysicalHashAggregateOperator) expression1.getOp()).getAggregations();
            assertEquals(Type.INT, column2.getType());
            assertEquals(Type.BIGINT, aggs.get(column2).getType());
        } catch (Exception e) {
            fail("sql should execute normally");
        }

    }

    @Test
    public void testPruneCountStarRule(@Mocked OlapTable olapTable1) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getRowCount();
                result = 10000;
                minTimes = 0;
            }
        };

        CallOperator call = new CallOperator(FunctionSet.COUNT, Type.BIGINT, Lists.newArrayList());
        new Expectations(call) {
            {
                call.getUsedColumns();
                result = new ColumnRefSet();
                minTimes = 0;

                call.getFunction();
                minTimes = 0;
                result = Expr.getBuiltinFunction(FunctionSet.COUNT,
                        new Type[] {}, Function.CompareMode.IS_IDENTICAL);
            }
        };

        Map<ColumnRefOperator, ScalarOperator> projectColumnMap = Maps.newHashMap();
        projectColumnMap.put(column5, column5);

        List<ColumnRefOperator> scanColumns = Lists.newArrayList();
        scanColumns.add(column1);
        scanColumns.add(column2);
        scanColumns.add(column3);
        scanColumns.add(column4);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));
        scanColumnMap.put(column3, new Column("t3", ScalarType.INT, true));
        scanColumnMap.put(column4, new Column("t4", ScalarType.INT, true));

        new Expectations() {
            {
                olapTable1.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }
        };

        Map<ColumnRefOperator, CallOperator> map = Maps.newHashMap();
        map.put(column5, call);
        LogicalAggregationOperator aggregationOperator =
                new LogicalAggregationOperator(AggType.GLOBAL, Lists.newArrayList(), map);

        OptExpression expression = OptExpression.create(new LogicalProjectOperator(projectColumnMap),
                OptExpression.create(aggregationOperator,
                        OptExpression.create(
                                new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(),
                                        null, -1,
                                        null))));

        ColumnRefSet outputColumns = new ColumnRefSet(column5.getId());

        try {
            Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
            optimizer.optimize(expression, outputColumns);

            fail("invalid plan. can not optimize success");
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("Type check failed. the type of arg 5: t5 in " +
                    "expr '5: t5' is defined as INT, but the actual type is BIGINT"), e.getMessage());
        }
    }

    @Test
    public void testPruneAggregateConstantRule(@Mocked OlapTable olapTable1) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;
            }
        };

        Map<ColumnRefOperator, ScalarOperator> projectColumnMap = Maps.newHashMap();
        projectColumnMap.put(column4, column3);

        List<ColumnRefOperator> scanColumns = Lists.newArrayList();
        scanColumns.add(column1);
        scanColumns.add(column2);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));

        Map<ColumnRefOperator, CallOperator> map = Maps.newHashMap();
        map.put(column3, call);
        LogicalAggregationOperator aggregationOperator =
                new LogicalAggregationOperator(AggType.GLOBAL, Lists.newArrayList(), map);

        OptExpression expression = OptExpression.create(new LogicalProjectOperator(projectColumnMap),
                OptExpression.create(aggregationOperator,
                        OptExpression.create(
                                new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(),
                                        null, -1,
                                        null))));

        ColumnRefSet outputColumns = new ColumnRefSet(column4.getId());

        new Expectations() {
            {
                olapTable1.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }
        };

        try {
            Optimizer optimizer = OptimizerFactory.create(
                    OptimizerFactory.mockContext(ctx, columnRefFactory));
            optimizer.optimize(expression, outputColumns);

            fail("invalid plan. can not optimize success");
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("Type check failed. the type of arg 3: t3 in " +
                    "expr '3: t3' is defined as INT, but the actual type is BIGINT"), e.getMessage());
        }

    }

    @Test
    public void testMergeAggregateWithLimitRule(@Mocked OlapTable olapTable1) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;
            }
        };

        Map<ColumnRefOperator, ScalarOperator> projectColumnMap = Maps.newHashMap();
        projectColumnMap.put(column4, column3);

        List<ColumnRefOperator> scanColumns = Lists.newArrayList();
        scanColumns.add(column1);
        scanColumns.add(column2);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));

        Map<ColumnRefOperator, CallOperator> map = Maps.newHashMap();
        map.put(column3, call);
        LogicalAggregationOperator aggregationOperator =
                new LogicalAggregationOperator(AggType.GLOBAL, Lists.newArrayList(), map);

        OptExpression agg = OptExpression.create(aggregationOperator,
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null, -1,
                                null)));

        OptExpression limit = OptExpression.create(LogicalLimitOperator.init(1), agg);

        OptExpression expression = OptExpression.create(
                new LogicalProjectOperator(projectColumnMap), limit);

        ColumnRefSet outputColumns = new ColumnRefSet(column4.getId());

        new Expectations() {
            {
                olapTable1.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }
        };

        try {
            Optimizer optimizer = OptimizerFactory.create(
                    OptimizerFactory.mockContext(ctx, columnRefFactory));
            optimizer.optimize(expression, outputColumns);

            fail("invalid plan. can not optimize success");
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("Type check failed. the type of arg 3: t3 in " +
                    "expr '3: t3' is defined as INT, but the actual type is BIGINT"), e.getMessage());
        }

    }

    @Test
    public void testPruneSortColumnsRule(@Mocked OlapTable olapTable1,
                                         @Mocked CallOperator call) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;
            }
        };

        Map<ColumnRefOperator, ScalarOperator> projectColumnMap = Maps.newHashMap();
        projectColumnMap.put(column2, column2);

        List<ColumnRefOperator> scanColumns = Lists.newArrayList();

        scanColumns.add(column1);
        scanColumns.add(column2);
        scanColumns.add(column3);
        scanColumns.add(column4);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));
        scanColumnMap.put(column3, new Column("t3", ScalarType.INT, true));
        scanColumnMap.put(column4, new Column("t4", ScalarType.INT, true));

        LogicalTopNOperator sortOperator = new LogicalTopNOperator(
                Lists.newArrayList(new Ordering(column1, false, false)));

        OptExpression expression = OptExpression.create(new LogicalProjectOperator(projectColumnMap),
                OptExpression.create(sortOperator,
                        OptExpression.create(
                                new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(),
                                        null, -1,
                                        null))));

        ColumnRefSet outputColumns = new ColumnRefSet(column2.getId());

        Optimizer optimizer = OptimizerFactory.create(
                OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(expression, outputColumns);

        assertEquals(physicalTree.getLogicalProperty().getOutputColumns(), new ColumnRefSet(column2.getId()));

        assertEquals(physicalTree.inputAt(0).getLogicalProperty().getOutputColumns(),
                new ColumnRefSet(Lists.newArrayList(column1, column2)));

        Memo memo = optimizer.getContext().getMemo();
        PhysicalOlapScanOperator
                scan = (PhysicalOlapScanOperator) memo.getGroups().get(0).getPhysicalExpressions().get(0).getOp();
        assertEquals(scan.getOutputColumns(), Lists.newArrayList(column1, column2));

        assertEquals(optimizer.getContext().getMemo().getRootGroup().
                getLogicalProperty().getOutputColumns(), new ColumnRefSet(column2.getId()));
    }

    @Test
    public void testSplitAggregateRule(@Mocked OlapTable olapTable1) {
        ctx.getSessionVariable().setNewPlanerAggStage(2);
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getRowCount();
                result = 10000;
                minTimes = 0;

                olapTable1.getType();
                result = Table.TableType.OLAP;
                minTimes = 0;
            }
        };

        ColumnRefOperator column1 = columnRefFactory.create("t1", ScalarType.INT, true);

        List<ColumnRefOperator> scanColumns = Lists.newArrayList(column1);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("k1", Type.INT));

        Map<ColumnRefOperator, CallOperator> map = Maps.newHashMap();
        LogicalAggregationOperator aggregationOperator =
                new LogicalAggregationOperator(AggType.GLOBAL, Lists.newArrayList(column1), map);

        OptExpression expression = OptExpression.create(aggregationOperator,
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null, -1,
                                null)));

        ColumnRefSet outputColumns = new ColumnRefSet(column1.getId());

        Optimizer optimizer = OptimizerFactory.create(
                OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(expression, outputColumns);

        Operator operator = physicalTree.getOp();
        assertEquals(operator.getOpType(), OperatorType.PHYSICAL_HASH_AGG);
        PhysicalHashAggregateOperator globalAgg = (PhysicalHashAggregateOperator) operator;
        assertTrue(globalAgg.getType().isGlobal());

        operator = physicalTree.inputAt(0).getOp();
        assertEquals(operator.getOpType(), OperatorType.PHYSICAL_DISTRIBUTION);

        operator = physicalTree.inputAt(0).inputAt(0).getOp();
        assertEquals(operator.getOpType(), OperatorType.PHYSICAL_HASH_AGG);
        PhysicalHashAggregateOperator localAgg = (PhysicalHashAggregateOperator) operator;
        assertTrue(localAgg.getType().isLocal());

        operator = physicalTree.inputAt(0).inputAt(0).inputAt(0).getOp();
        assertEquals(operator.getOpType(), OperatorType.PHYSICAL_OLAP_SCAN);
        ctx.getSessionVariable().setNewPlanerAggStage(0);
    }

    @Test
    public void testSplitAggregateRuleNoGroupBy(@Mocked OlapTable olapTable1) {
        ctx.getSessionVariable().setNewPlanerAggStage(2);
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getRowCount();
                result = 10000;
                minTimes = 0;

                olapTable1.getType();
                result = Table.TableType.OLAP;
                minTimes = 0;
            }
        };

        new Expectations(call) {
            {
                call.isDistinct();
                result = false;
                minTimes = 0;
            }
        };

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));

        Map<ColumnRefOperator, CallOperator> map = Maps.newHashMap();
        map.put(column3, call);
        LogicalAggregationOperator aggregationOperator =
                new LogicalAggregationOperator(AggType.GLOBAL, Lists.newArrayList(), map);
        LogicalOlapScanOperator scanOperator =
                new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null, -1, null);

        OptExpression expression = OptExpression.create(aggregationOperator, OptExpression.create(scanOperator));

        ColumnRefSet outputColumns = new ColumnRefSet(Lists.newArrayList(column3));

        new Expectations() {
            {
                olapTable1.getBaseSchema();
                result = new ArrayList<>(scanColumnMap.values());
                minTimes = 0;
            }
        };

        try {
            Optimizer optimizer = OptimizerFactory.create(
                    OptimizerFactory.mockContext(ctx, columnRefFactory));
            optimizer.optimize(expression, outputColumns);

            fail("invalid plan. can not optimize success");
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("Type check failed. the type of arg 3: t3 in " +
                    "expr '3: t3' is defined as INT, but the actual type is BIGINT"), e.getMessage());
        }
    }

    @Test
    public void testSplitAggregateRuleWithDistinctAndGroupBy(@Mocked OlapTable olapTable1) {
        ctx.getSessionVariable().setNewPlanerAggStage(3);
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getRowCount();
                result = 10000;
                minTimes = 0;
            }
        };

        CallOperator call =
                new CallOperator(FunctionSet.SUM, Type.BIGINT, Lists.newArrayList(ConstantOperator.createInt(1)),
                        null, true);

        new Expectations(call) {
            {
                call.getUsedColumns();
                result = new ColumnRefSet(1);
                minTimes = 0;


                call.getFunction();
                result = AggregateFunction.createBuiltin(FunctionSet.SUM,
                        Lists.<Type>newArrayList(Type.INT), Type.BIGINT, Type.BIGINT, false, true, false);
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> scanColumns = Lists.newArrayList(column1, column2);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));

        Map<ColumnRefOperator, CallOperator> map = Maps.newHashMap();
        map.put(column3, call);
        LogicalAggregationOperator aggregationOperator =
                new LogicalAggregationOperator(AggType.GLOBAL, Lists.newArrayList(column2), map);

        OptExpression expression = OptExpression.create(aggregationOperator,
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null, -1,
                                null)));

        ColumnRefSet outputColumns = new ColumnRefSet(Lists.newArrayList(column3, column2));

        try {
            Optimizer optimizer = OptimizerFactory.create(
                    OptimizerFactory.mockContext(ctx, columnRefFactory));
            optimizer.optimize(expression, outputColumns);
            fail("invalid plan. can not optimize success");
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("Type check failed. the type of arg 3: t3 in " +
                    "expr '3: t3' is defined as INT, but the actual type is BIGINT"), e.getMessage());
        }

    }

    @Test
    public void testSplitAggregateRuleWithOnlyOneDistinct(@Mocked OlapTable olapTable1) {
        ctx.getSessionVariable().setNewPlanerAggStage(4);
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getRowCount();
                result = 10000;
                minTimes = 0;
            }
        };

        CallOperator call =
                new CallOperator(FunctionSet.COUNT, Type.BIGINT, Lists.newArrayList(ConstantOperator.createInt(1)),
                        null, true);

        new Expectations(call) {
            {
                call.getUsedColumns();
                result = new ColumnRefSet(1);
                minTimes = 0;

                call.getFunction();
                result = AggregateFunction.createBuiltin(FunctionSet.COUNT,
                        Lists.<Type>newArrayList(Type.INT), Type.BIGINT, Type.BIGINT, false, true, false);
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> scanColumns = Lists.newArrayList(column1, column2);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));

        Map<ColumnRefOperator, CallOperator> map = Maps.newHashMap();
        map.put(column3, call);
        LogicalAggregationOperator aggregationOperator =
                new LogicalAggregationOperator(AggType.GLOBAL, Lists.newArrayList(), map);

        OptExpression expression = OptExpression.create(aggregationOperator,
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null, -1,
                                null)));

        ColumnRefSet outputColumns = new ColumnRefSet(Lists.newArrayList(column3));

        try {
            Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
            optimizer.optimize(expression, outputColumns);

            fail("invalid plan. can not optimize success");
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("Type check failed. the type of arg 3: t3 in " +
                    "expr '3: t3' is defined as INT, but the actual type is BIGINT"), e.getMessage());
        }

    }

    @Test
    public void testSplitAggregateRuleWithProject(@Mocked OlapTable olapTable1) {
        ctx.getSessionVariable().setNewPlanerAggStage(2);
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getRowCount();
                result = 10000;
                minTimes = 0;

                olapTable1.getType();
                result = Table.TableType.OLAP;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> scanColumns = Lists.newArrayList(column1);
        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("k1", Type.INT));

        Map<ColumnRefOperator, ScalarOperator> projectColumnMap = Maps.newHashMap();
        projectColumnMap.put(column1, column1);

        Map<ColumnRefOperator, CallOperator> map = Maps.newHashMap();
        LogicalAggregationOperator aggregationOperator =
                new LogicalAggregationOperator(AggType.GLOBAL, Lists.newArrayList(column1), map);

        Map<ColumnRefOperator, ScalarOperator> projectColumnMap2 = Maps.newHashMap();
        projectColumnMap2.put(column2, column1);

        OptExpression project = OptExpression.create(new LogicalProjectOperator(projectColumnMap),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null, -1,
                                null)));

        OptExpression agg = OptExpression.create(aggregationOperator, project);
        OptExpression topProject = OptExpression.create(new LogicalProjectOperator(projectColumnMap2),
                agg);

        ColumnRefSet outputColumns = new ColumnRefSet(column2.getId());

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(topProject, outputColumns);

        Operator operator = physicalTree.getOp();
        assertEquals(operator.getOpType(), OperatorType.PHYSICAL_HASH_AGG);
        PhysicalHashAggregateOperator globalAgg = (PhysicalHashAggregateOperator) operator;
        assertTrue(globalAgg.getType().isGlobal());

        operator = physicalTree.inputAt(0).getOp();
        assertEquals(operator.getOpType(), OperatorType.PHYSICAL_DISTRIBUTION);

        operator = physicalTree.inputAt(0).inputAt(0).getOp();
        assertEquals(operator.getOpType(), OperatorType.PHYSICAL_HASH_AGG);
        PhysicalHashAggregateOperator localAgg = (PhysicalHashAggregateOperator) operator;
        assertTrue(localAgg.getType().isLocal());

        operator = physicalTree.inputAt(0).inputAt(0).inputAt(0).getOp();
        assertEquals(operator.getOpType(), OperatorType.PHYSICAL_OLAP_SCAN);
        ctx.getSessionVariable().setNewPlanerAggStage(0);
    }

    @Test
    public void testFilterPushDownWithHaving(@Mocked OlapTable olapTable1) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> scanColumns = Lists.newArrayList(column1, column2);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));

        Map<ColumnRefOperator, ScalarOperator> projectColumnMap1 = Maps.newHashMap();
        projectColumnMap1.put(column3, column1);
        projectColumnMap1.put(column4, column2);

        Map<ColumnRefOperator, CallOperator> map = Maps.newHashMap();
        map.put(column5, call);
        LogicalAggregationOperator aggregationOperator =
                new LogicalAggregationOperator(AggType.GLOBAL, Lists.newArrayList(column4), map);

        BinaryPredicateOperator predicate = new BinaryPredicateOperator(BinaryType.GT,
                column5,
                ConstantOperator.createInt(1));
        LogicalFilterOperator filterOperator = new LogicalFilterOperator(predicate);

        Map<ColumnRefOperator, ScalarOperator> projectColumnMap2 = Maps.newHashMap();
        projectColumnMap2.put(column6, column5);

        OptExpression projectExpression = OptExpression.create(new LogicalProjectOperator(projectColumnMap1),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null, -1,
                                null)));

        OptExpression aggExpression = OptExpression.create(aggregationOperator, projectExpression);
        OptExpression havingExpression = OptExpression.create(filterOperator, aggExpression);
        OptExpression root = OptExpression.create(new LogicalProjectOperator(projectColumnMap2), havingExpression);

        ColumnRefSet outputColumns = new ColumnRefSet(Lists.newArrayList(column6));

        try {
            Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
            optimizer.optimize(root, outputColumns);

            fail("invalid plan. can not optimize success");
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("Type check failed. the type of arg 5: t5 in " +
                    "expr '5: t5' is defined as INT, but the actual type is BIGINT"), e.getMessage());
        }
    }

    @Test
    public void testFilterPushDownWithHaving2(@Mocked OlapTable olapTable1) {
        ctx.getSessionVariable().setNewPlanerAggStage(2);
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getRowCount();
                result = 10000;
                minTimes = 0;

                olapTable1.getType();
                result = Table.TableType.OLAP;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> scanColumns = Lists.newArrayList(column1, column2);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("k1", Type.INT));

        Map<ColumnRefOperator, ScalarOperator> projectColumnMap1 = Maps.newHashMap();
        projectColumnMap1.put(column3, column1);

        LogicalAggregationOperator aggregationOperator =
                new LogicalAggregationOperator(AggType.GLOBAL, Lists.newArrayList(column3), Maps.newHashMap());

        BinaryPredicateOperator predicate = new BinaryPredicateOperator(BinaryType.GT,
                column3,
                ConstantOperator.createInt(1));
        LogicalFilterOperator filterOperator = new LogicalFilterOperator(predicate);

        Map<ColumnRefOperator, ScalarOperator> projectColumnMap2 = Maps.newHashMap();
        projectColumnMap2.put(column4, column3);

        OptExpression projectExpression = OptExpression.create(new LogicalProjectOperator(projectColumnMap1),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null, -1,
                                null)));

        OptExpression aggExpression = OptExpression.create(aggregationOperator, projectExpression);
        OptExpression havingExpression = OptExpression.create(filterOperator, aggExpression);
        OptExpression root = OptExpression.create(new LogicalProjectOperator(projectColumnMap2), havingExpression);

        ColumnRefSet outputColumns = new ColumnRefSet(Lists.newArrayList(column4));

        Optimizer optimizer = OptimizerFactory.create(
                OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(root, outputColumns);

        Operator operator = physicalTree.getOp();
        assertEquals(operator.getOpType(), OperatorType.PHYSICAL_HASH_AGG);
        PhysicalHashAggregateOperator globalAgg = (PhysicalHashAggregateOperator) operator;
        assertNull(globalAgg.getPredicate());

        operator = physicalTree.inputAt(0).inputAt(0).inputAt(0).getOp();
        assertEquals(operator.getOpType(), OperatorType.PHYSICAL_OLAP_SCAN);
        PhysicalOlapScanOperator scan = (PhysicalOlapScanOperator) operator;
        assertNotNull(scan.getPredicate());
        ctx.getSessionVariable().setNewPlanerAggStage(0);
    }

    @Test
    public void testFilterPushDownRule(@Mocked OlapTable olapTable1) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> outputColumns = Lists.newArrayList(column4);

        Map<ColumnRefOperator, ScalarOperator> projectMap = Maps.newHashMap();
        projectMap.put(column4, column1);

        List<ColumnRefOperator> scanColumns = Lists.newArrayList();

        scanColumns.add(column1);
        scanColumns.add(column2);
        scanColumns.add(column3);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));
        scanColumnMap.put(column3, new Column("t3", ScalarType.INT, true));

        BinaryPredicateOperator predicate = new BinaryPredicateOperator(BinaryType.EQ,
                column2,
                ConstantOperator.createInt(1));

        OptExpression expression = OptExpression.create(new LogicalProjectOperator(projectMap),
                OptExpression.create(new LogicalFilterOperator(predicate),
                        OptExpression.create(
                                new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(),
                                        null, -1,
                                        null))));

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(expression, new ColumnRefSet(outputColumns));

        assertNotNull(physicalTree.getOp().getProjection());
        assertEquals(physicalTree.getOp().getOpType(), OperatorType.PHYSICAL_OLAP_SCAN);
        PhysicalOlapScanOperator physicalOlapScan = (PhysicalOlapScanOperator) physicalTree.getOp();
        assertTrue(physicalOlapScan.getPredicate() instanceof BinaryPredicateOperator);

        assertTrue(physicalOlapScan.getColRefToColumnMetaMap().containsKey(column2));
    }

    @Test
    public void testFilterPushDownRuleWithMultiProjects(@Mocked OlapTable olapTable1) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> outputColumns = Lists.newArrayList(column5);

        Map<ColumnRefOperator, ScalarOperator> projectMap = Maps.newHashMap();
        projectMap.put(column4, column1);

        Map<ColumnRefOperator, ScalarOperator> projectMap2 = Maps.newHashMap();
        projectMap2.put(column5, column4);

        List<ColumnRefOperator> scanColumns = Lists.newArrayList(column1, column2);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));

        BinaryPredicateOperator predicate = new BinaryPredicateOperator(BinaryType.EQ,
                column2,
                ConstantOperator.createInt(1));

        OptExpression project1 = OptExpression.create(new LogicalProjectOperator(projectMap),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null, -1,
                                null)));

        OptExpression project2 = OptExpression.create(new LogicalProjectOperator(projectMap2), project1);

        OptExpression filter = OptExpression.create(new LogicalFilterOperator(predicate), project2);

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(filter, new ColumnRefSet(outputColumns));

        assertNotNull(physicalTree.getOp().getProjection());
        Projection pp = physicalTree.getOp().getProjection();

        assertEquals(1, pp.getColumnRefMap().size());
        assertTrue(pp.getColumnRefMap().containsKey(column5));
        assertEquals(column1, pp.getColumnRefMap().get(column5));

        Operator operator = physicalTree.getOp();
        assertEquals(operator.getOpType(), OperatorType.PHYSICAL_OLAP_SCAN);
        PhysicalOlapScanOperator physicalOlapScan = (PhysicalOlapScanOperator) operator;
        assertTrue(physicalOlapScan.getPredicate() instanceof BinaryPredicateOperator);
    }

    @Test
    public void testCommonOperatorReuseRule(@Mocked OlapTable olapTable1) {
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> outputColumns = Lists.newArrayList(column4, column5);

        CallOperator add1 = new CallOperator("add", Type.INT,
                Lists.newArrayList(column1, ConstantOperator.createInt(2)));

        CallOperator add2 = new CallOperator("add", Type.INT,
                Lists.newArrayList(add1, ConstantOperator.createInt(3)));

        new Expectations(add1, add2) {
            {
                add1.getFunction();
                minTimes = 0;
                result = new Function(new FunctionName("add"), new Type[] {Type.INT, Type.INT}, Type.INT, false);

                add2.getFunction();
                minTimes = 0;
                result = new Function(new FunctionName("add"), new Type[] {Type.INT, Type.INT}, Type.INT, false);
            }
        };

        Map<ColumnRefOperator, ScalarOperator> projectMap = Maps.newHashMap();
        projectMap.put(column4, add1);
        projectMap.put(column5, add2);

        List<ColumnRefOperator> scanColumns = Lists.newArrayList(column1, column2, column3);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scanColumnMap.put(column2, new Column("t2", ScalarType.INT, true));
        scanColumnMap.put(column3, new Column("t3", ScalarType.INT, true));

        OptExpression expression = OptExpression.create(new LogicalProjectOperator(projectMap),
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null, -1,
                                null)));

        Optimizer optimizer = OptimizerFactory.create(
                OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(expression, new ColumnRefSet(outputColumns));

        PhysicalOlapScanOperator olapScanOperator = (PhysicalOlapScanOperator) physicalTree.getOp();
        Projection projection = olapScanOperator.getProjection();
        assertEquals(projection.getCommonSubOperatorMap().size(), 1);

        ColumnRefOperator column7 = columnRefFactory.getColumnRef(7);
        assertTrue(projection.getCommonSubOperatorMap().containsKey(column7));
        assertTrue(projection.getCommonSubOperatorMap().get(column7) instanceof CallOperator);
        CallOperator res = (CallOperator) projection.getCommonSubOperatorMap().get(column7);
        add1.setFunction(res.getFunction());
        assertEquals(res, add1);

        assertEquals(physicalTree.getOp().getOpType(), OperatorType.PHYSICAL_OLAP_SCAN);
        PhysicalOlapScanOperator physicalOlapScan = (PhysicalOlapScanOperator) physicalTree.getOp();
        assertEquals(physicalOlapScan.getProjection().getOutputColumns(), Lists.newArrayList(column4, column5));

        assertEquals(optimizer.getContext().getMemo().getRootGroup().
                getLogicalProperty().getOutputColumns(), new ColumnRefSet(outputColumns));
    }

    @Test
    public void testShuffleTwoJoin(@Mocked OlapTable olapTable1,
                                   @Mocked OlapTable olapTable2) {
        List<Column> columnList1 = new ArrayList<>();
        Column column2 = new Column(this.column2.getName(), ScalarType.INT);
        columnList1.add(column2);
        HashDistributionInfo hashDistributionInfo1 = new HashDistributionInfo(3, columnList1);

        List<Column> columnList2 = new ArrayList<>();
        Column column4 = new Column(this.column4.getName(), ScalarType.INT);
        columnList2.add(column4);
        HashDistributionInfo hashDistributionInfo2 = new HashDistributionInfo(3, columnList2);
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getDefaultDistributionInfo();
                result = hashDistributionInfo1;
                minTimes = 0;
            }

            {
                olapTable2.getId();
                result = 1;
                minTimes = 0;

                olapTable2.getDefaultDistributionInfo();
                result = hashDistributionInfo2;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> outputColumns = Lists.newArrayList(column1, column3);

        List<ColumnRefOperator> scan1Columns = Lists.newArrayList(column1, this.column2);
        List<ColumnRefOperator> scan2Columns = Lists.newArrayList(column3, this.column4);

        Map<ColumnRefOperator, Column> scan1ColumnMap = Maps.newHashMap();
        scan1ColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scan1ColumnMap.put(this.column2, new Column("t2", ScalarType.INT, true));

        Map<ColumnRefOperator, Column> scan2ColumnMap = Maps.newHashMap();
        scan2ColumnMap.put(column3, new Column("t3", ScalarType.INT, true));
        scan2ColumnMap.put(this.column4, new Column("t4", ScalarType.INT, true));

        BinaryPredicateOperator predicate = new BinaryPredicateOperator(BinaryType.EQ,
                column1,
                column3);

        LogicalOlapScanOperator scan1 =
                new LogicalOlapScanOperator(olapTable1, scan1ColumnMap, Maps.newHashMap(), null, -1,
                        null);
        LogicalOlapScanOperator scan2 =
                new LogicalOlapScanOperator(olapTable2, scan2ColumnMap, Maps.newHashMap(), null, -1,
                        null);
        LogicalJoinOperator join = new LogicalJoinOperator(JoinOperator.INNER_JOIN, predicate);

        OptExpression expression = OptExpression.create(join,
                OptExpression.create(scan1),
                OptExpression.create(scan2));

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(expression, new ColumnRefSet(outputColumns));
    }

    @Test
    public void testShuffleThreeJoin(@Mocked OlapTable olapTable1,
                                     @Mocked OlapTable olapTable2,
                                     @Mocked OlapTable olapTable3) {
        List<Column> columnList1 = new ArrayList<>();
        Column column2 = new Column(this.column2.getName(), ScalarType.INT);
        columnList1.add(column2);
        HashDistributionInfo hashDistributionInfo1 = new HashDistributionInfo(3, columnList1);

        List<Column> columnList2 = new ArrayList<>();
        Column column4 = new Column(this.column4.getName(), ScalarType.INT);
        columnList2.add(column4);
        HashDistributionInfo hashDistributionInfo2 = new HashDistributionInfo(3, columnList2);

        List<Column> columnList3 = new ArrayList<>();
        Column column6 = new Column(this.column6.getName(), ScalarType.INT);
        columnList3.add(column6);
        HashDistributionInfo hashDistributionInfo3 = new HashDistributionInfo(3, columnList3);
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getDefaultDistributionInfo();
                result = hashDistributionInfo1;
                minTimes = 0;
            }

            {
                olapTable2.getId();
                result = 1;
                minTimes = 0;

                olapTable2.getDefaultDistributionInfo();
                result = hashDistributionInfo2;
                minTimes = 0;
            }

            {
                olapTable3.getId();
                result = 2;
                minTimes = 0;

                olapTable3.getDefaultDistributionInfo();
                result = hashDistributionInfo3;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> outputColumns = Lists.newArrayList(column1, column3, column5);

        List<ColumnRefOperator> scan1Columns = Lists.newArrayList(column1, this.column2);
        List<ColumnRefOperator> scan2Columns = Lists.newArrayList(column3, this.column4);
        List<ColumnRefOperator> scan3Columns = Lists.newArrayList(column5, this.column6);

        Map<ColumnRefOperator, Column> scan1ColumnMap = Maps.newHashMap();
        scan1ColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scan1ColumnMap.put(this.column2, new Column("t2", ScalarType.INT, true));

        Map<ColumnRefOperator, Column> scan2ColumnMap = Maps.newHashMap();
        scan2ColumnMap.put(column3, new Column("t3", ScalarType.INT, true));
        scan2ColumnMap.put(this.column4, new Column("t4", ScalarType.INT, true));

        Map<ColumnRefOperator, Column> scan3ColumnMap = Maps.newHashMap();
        scan3ColumnMap.put(column5, new Column("t5", ScalarType.INT, true));
        scan3ColumnMap.put(this.column6, new Column("t6", ScalarType.INT, true));

        BinaryPredicateOperator predicate = new BinaryPredicateOperator(BinaryType.EQ,
                column1,
                column3);

        BinaryPredicateOperator predicate2 = new BinaryPredicateOperator(BinaryType.EQ,
                column1,
                column5);

        LogicalOlapScanOperator scan1 =
                new LogicalOlapScanOperator(olapTable1, scan1ColumnMap, Maps.newHashMap(), null, -1,
                        null);
        LogicalOlapScanOperator scan2 =
                new LogicalOlapScanOperator(olapTable2, scan2ColumnMap, Maps.newHashMap(), null, -1,
                        null);
        LogicalOlapScanOperator scan3 =
                new LogicalOlapScanOperator(olapTable3, scan3ColumnMap, Maps.newHashMap(), null, -1,
                        null);
        LogicalJoinOperator join = new LogicalJoinOperator(JoinOperator.INNER_JOIN, predicate);
        LogicalJoinOperator join2 = new LogicalJoinOperator(JoinOperator.INNER_JOIN, predicate2);

        OptExpression join1 = OptExpression.create(join,
                OptExpression.create(scan1),
                OptExpression.create(scan2));
        OptExpression topJoin = OptExpression.create(join2,
                join1, OptExpression.create(scan3));
        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(topJoin, new ColumnRefSet(outputColumns));
    }

    @Test
    public void testBroadcastExceedRowLimitWithHugeGapInRowCount(@Mocked OlapTable olapTable1,
                                                                 @Mocked OlapTable olapTable2) throws Exception {
        List<Column> columnList1 = new ArrayList<>();
        Column column2 = new Column(this.column2.getName(), ScalarType.INT);
        columnList1.add(column2);
        HashDistributionInfo hashDistributionInfo1 = new HashDistributionInfo(3, columnList1);

        List<Column> columnList2 = new ArrayList<>();
        Column column4 = new Column(this.column4.getName(), ScalarType.INT);
        columnList2.add(column4);
        HashDistributionInfo hashDistributionInfo2 = new HashDistributionInfo(3, columnList2);

        MaterializedIndex m1 = new MaterializedIndex();
        m1.setRowCount(100000000);
        Partition p1 = new Partition(0, 10, "p1", m1, hashDistributionInfo1);

        MaterializedIndex m2 = new MaterializedIndex();
        m2.setRowCount(20000000);
        Partition p2 = new Partition(1, 11, "p2", m2, hashDistributionInfo2);
        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getPartitions();
                result = Lists.newArrayList(p1);
                minTimes = 0;

                olapTable1.getPartition(anyLong);
                result = p1;
                minTimes = 0;

                olapTable1.getDefaultDistributionInfo();
                result = hashDistributionInfo1;
                minTimes = 0;

                olapTable1.getType();
                result = Table.TableType.OLAP;
                minTimes = 0;
            }

            {
                olapTable2.getId();
                result = 1;
                minTimes = 0;

                olapTable2.getPartitions();
                result = Lists.newArrayList(p2);
                minTimes = 0;

                olapTable2.getPartition(anyLong);
                result = p2;
                minTimes = 0;

                olapTable2.getDefaultDistributionInfo();
                result = hashDistributionInfo2;
                minTimes = 0;

                olapTable2.getType();
                result = Table.TableType.OLAP;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> outputColumns = Lists.newArrayList(column1, column3);

        List<ColumnRefOperator> scan1Columns = Lists.newArrayList(column1, this.column2);
        List<ColumnRefOperator> scan2Columns = Lists.newArrayList(column3, this.column4);

        Map<ColumnRefOperator, Column> scan1ColumnMap = Maps.newHashMap();
        scan1ColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scan1ColumnMap.put(this.column2, new Column("t2", ScalarType.INT, true));

        Map<ColumnRefOperator, Column> scan2ColumnMap = Maps.newHashMap();
        scan2ColumnMap.put(column3, new Column("t3", ScalarType.INT, true));
        scan2ColumnMap.put(this.column4, new Column("t4", ScalarType.INT, true));

        BinaryPredicateOperator predicate = new BinaryPredicateOperator(BinaryType.EQ,
                column1,
                column3);

        LogicalOlapScanOperator scan1 =
                new LogicalOlapScanOperator(olapTable1, scan1ColumnMap, Maps.newHashMap(), null, -1,
                        null);
        LogicalOlapScanOperator scan2 =
                new LogicalOlapScanOperator(olapTable2, scan2ColumnMap, Maps.newHashMap(), null, -1,
                        null);
        LogicalJoinOperator join = new LogicalJoinOperator(JoinOperator.INNER_JOIN, predicate);
        OptExpression expression = OptExpression.create(join,
                OptExpression.create(scan1),
                OptExpression.create(scan2));

        Optimizer optimizer = OptimizerFactory.create(
                OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(expression, new ColumnRefSet(outputColumns));

        assertEquals(physicalTree.getInputs().get(1).getOp().getOpType(), OperatorType.PHYSICAL_DISTRIBUTION);
        PhysicalDistributionOperator rightOperator =
                (PhysicalDistributionOperator) physicalTree.getInputs().get(1).getOp();
        assertEquals(rightOperator.getDistributionSpec().getType(), DistributionSpec.DistributionType.BROADCAST);
    }

    @Test
    public void testBroadcastExceedRowLimitWithoutHugeGapInRowCount(@Mocked OlapTable olapTable1,
                                                                    @Mocked OlapTable olapTable2) throws Exception {
        FeConstants.runningUnitTest = true;
        List<Column> columnList1 = new ArrayList<>();
        Column column2 = new Column(this.column2.getName(), ScalarType.INT);
        columnList1.add(column2);
        HashDistributionInfo hashDistributionInfo1 = new HashDistributionInfo(3, columnList1);

        List<Column> columnList2 = new ArrayList<>();
        Column column4 = new Column(this.column4.getName(), ScalarType.INT);
        columnList2.add(column4);
        HashDistributionInfo hashDistributionInfo2 = new HashDistributionInfo(3, columnList2);

        Map<ColumnId, Column> idToColumn = Maps.newTreeMap(ColumnId.CASE_INSENSITIVE_ORDER);
        idToColumn.put(column2.getColumnId(), column2);
        idToColumn.put(column4.getColumnId(), column4);

        MaterializedIndex m1 = new MaterializedIndex();
        m1.setRowCount(100000000);
        Partition p1 = new Partition(0, 10, "p1", m1, hashDistributionInfo1);

        MaterializedIndex m2 = new MaterializedIndex();
        m2.setRowCount(20000000);
        Partition p2 = new Partition(1, 11, "p2", m2, hashDistributionInfo2);

        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getVisiblePartitions();
                result = Lists.newArrayList(p1);
                minTimes = 0;

                olapTable1.getPartition(anyLong);
                result = p1;
                minTimes = 0;

                olapTable1.getDefaultDistributionInfo();
                result = hashDistributionInfo1;
                minTimes = 0;

                olapTable1.isNativeTableOrMaterializedView();
                result = true;
                minTimes = 0;

                olapTable1.getIdToColumn();
                result = idToColumn;
                minTimes = 0;
            }

            {
                olapTable2.getId();
                result = 1;
                minTimes = 0;

                olapTable2.getVisiblePartitions();
                result = Lists.newArrayList(p2);
                minTimes = 0;

                olapTable2.getPartition(anyLong);
                result = p2;
                minTimes = 0;

                olapTable2.getDefaultDistributionInfo();
                result = hashDistributionInfo2;
                minTimes = 0;

                olapTable2.isNativeTableOrMaterializedView();
                result = true;
                minTimes = 0;

                olapTable2.getIdToColumn();
                result = idToColumn;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> outputColumns = Lists.newArrayList(column1, column3);

        List<ColumnRefOperator> scan1Columns = Lists.newArrayList(column1, this.column2);
        List<ColumnRefOperator> scan2Columns = Lists.newArrayList(column3, this.column4);

        Map<ColumnRefOperator, Column> scan1ColumnMap = Maps.newHashMap();
        scan1ColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scan1ColumnMap.put(this.column2, new Column("t2", ScalarType.INT, true));

        Map<ColumnRefOperator, Column> scan2ColumnMap = Maps.newHashMap();
        scan2ColumnMap.put(column3, new Column("t3", ScalarType.INT, true));
        scan2ColumnMap.put(this.column4, new Column("t4", ScalarType.INT, true));

        BinaryPredicateOperator predicate = new BinaryPredicateOperator(BinaryType.EQ,
                column1,
                column3);

        LogicalOlapScanOperator scan1 =
                new LogicalOlapScanOperator(olapTable1, scan1ColumnMap, Maps.newHashMap(),
                        DistributionSpec.createHashDistributionSpec(
                                new HashDistributionDesc(Lists.newArrayList(this.column2.getId()),
                                        HashDistributionDesc.SourceType.LOCAL)), -1, null);
        LogicalOlapScanOperator scan2 =
                new LogicalOlapScanOperator(olapTable2, scan2ColumnMap, Maps.newHashMap(),
                        DistributionSpec.createHashDistributionSpec(
                                new HashDistributionDesc(Lists.newArrayList(this.column4.getId()),
                                        HashDistributionDesc.SourceType.LOCAL)), -1, null);
        LogicalJoinOperator join = new LogicalJoinOperator(JoinOperator.INNER_JOIN, predicate);
        OptExpression expression = OptExpression.create(join,
                OptExpression.create(scan1),
                OptExpression.create(scan2));

        Optimizer optimizer = OptimizerFactory.create(
                OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(expression, new ColumnRefSet(outputColumns));

        assertEquals(physicalTree.getInputs().get(1).getOp().getOpType(), OperatorType.PHYSICAL_DISTRIBUTION);
        PhysicalDistributionOperator rightOperator =
                (PhysicalDistributionOperator) physicalTree.getInputs().get(1).getOp();
        assertEquals(rightOperator.getDistributionSpec().getType(), DistributionSpec.DistributionType.SHUFFLE);
    }

    @Test
    public void testOlapTablePartitionRowCount(@Mocked OlapTable olapTable1,
                                               @Mocked OlapTable olapTable2) {
        FeConstants.runningUnitTest = true;
        List<Column> columnList1 = new ArrayList<>();
        Column column2 = new Column(this.column2.getName(), ScalarType.INT);
        columnList1.add(column2);
        HashDistributionInfo hashDistributionInfo1 = new HashDistributionInfo(3, columnList1);

        List<Column> columnList2 = new ArrayList<>();
        Column column4 = new Column(this.column4.getName(), ScalarType.INT);
        columnList2.add(column4);
        HashDistributionInfo hashDistributionInfo2 = new HashDistributionInfo(3, columnList2);

        MaterializedIndex m1 = new MaterializedIndex();
        m1.setRowCount(1000000);
        Partition p1 = new Partition(0, 10, "p1", m1, hashDistributionInfo1);

        MaterializedIndex m2 = new MaterializedIndex();
        m2.setRowCount(2000000);
        Partition p2 = new Partition(1, 11, "p2", m2, hashDistributionInfo2);

        Map<ColumnId, Column> idToColumn = Maps.newTreeMap(ColumnId.CASE_INSENSITIVE_ORDER);
        idToColumn.put(column2.getColumnId(), column2);
        idToColumn.put(column4.getColumnId(), column4);

        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getPartitions();
                result = Lists.newArrayList(p1, p2);
                minTimes = 0;

                olapTable1.getPartition(0);
                result = p1;
                minTimes = 0;

                olapTable1.getPartition(1);
                result = p2;
                minTimes = 0;

                olapTable1.getDefaultDistributionInfo();
                result = hashDistributionInfo1;
                minTimes = 0;

                olapTable1.isNativeTableOrMaterializedView();
                result = true;
                minTimes = 0;

                olapTable1.getIdToColumn();
                result = idToColumn;
                minTimes = 0;
            }

            {
                olapTable2.getId();
                result = 1;
                minTimes = 0;

                olapTable2.getPartitions();
                result = Lists.newArrayList(p2);
                minTimes = 0;

                olapTable2.getPartition(1);
                result = p2;
                minTimes = 0;

                olapTable2.getDefaultDistributionInfo();
                result = hashDistributionInfo2;
                minTimes = 0;

                olapTable2.isNativeTableOrMaterializedView();
                result = true;
                minTimes = 0;

                olapTable2.getIdToColumn();
                result = idToColumn;
                minTimes = 0;
            }
        };

        List<ColumnRefOperator> outputColumns = Lists.newArrayList(column1, column3);

        List<ColumnRefOperator> scan1Columns = Lists.newArrayList(column1, this.column2);
        List<ColumnRefOperator> scan2Columns = Lists.newArrayList(column3, this.column4);

        Map<ColumnRefOperator, Column> scan1ColumnMap = Maps.newHashMap();
        scan1ColumnMap.put(column1, new Column("t1", ScalarType.INT, true));
        scan1ColumnMap.put(this.column2, new Column("t2", ScalarType.INT, true));

        Map<ColumnRefOperator, Column> scan2ColumnMap = Maps.newHashMap();
        scan2ColumnMap.put(column3, new Column("t3", ScalarType.INT, true));
        scan2ColumnMap.put(this.column4, new Column("t4", ScalarType.INT, true));

        BinaryPredicateOperator predicate = new BinaryPredicateOperator(BinaryType.EQ,
                column1,
                column3);

        LogicalOlapScanOperator scan1 =
                new LogicalOlapScanOperator(olapTable1, scan1ColumnMap, Maps.newHashMap(),
                        DistributionSpec.createHashDistributionSpec(
                                new HashDistributionDesc(Lists.newArrayList(this.column2.getId()),
                                        HashDistributionDesc.SourceType.LOCAL)), -1, null);
        LogicalOlapScanOperator scan2 =
                new LogicalOlapScanOperator(olapTable2, scan2ColumnMap, Maps.newHashMap(),
                        DistributionSpec.createHashDistributionSpec(
                                new HashDistributionDesc(Lists.newArrayList(this.column4.getId()),
                                        HashDistributionDesc.SourceType.LOCAL)), -1, null);
        LogicalJoinOperator join = new LogicalJoinOperator(JoinOperator.INNER_JOIN, predicate);
        OptExpression expression = OptExpression.create(join,
                OptExpression.create(scan1),
                OptExpression.create(scan2));

        Optimizer optimizer = OptimizerFactory.create(
                OptimizerFactory.mockContext(ctx, columnRefFactory));
        OptExpression physicalTree = optimizer.optimize(expression, new ColumnRefSet(outputColumns));
        assertEquals(physicalTree.getInputs().get(1).getOp().getOpType(), OperatorType.PHYSICAL_DISTRIBUTION);
        PhysicalDistributionOperator rightOperator =
                (PhysicalDistributionOperator) physicalTree.getInputs().get(1).getOp();
        assertEquals(rightOperator.getDistributionSpec().getType(), DistributionSpec.DistributionType.BROADCAST);
        PhysicalOlapScanOperator
                rightScan = (PhysicalOlapScanOperator) physicalTree.getInputs().get(1).getInputs().get(0).getOp();
        assertEquals(olapTable2.getId(), rightScan.getTable().getId());

        new Expectations() {
            {
                olapTable1.getId();
                result = 0;
                minTimes = 0;

                olapTable1.getVisiblePartitions();
                result = Lists.newArrayList(p1);
                minTimes = 0;

                olapTable1.getPartition(anyLong);
                result = p1;
                minTimes = 0;

                olapTable1.getDefaultDistributionInfo();
                result = hashDistributionInfo1;
                minTimes = 0;

                olapTable1.isNativeTableOrMaterializedView();
                result = true;
                minTimes = 0;
            }

            {
                olapTable2.getId();
                result = 1;
                minTimes = 0;

                olapTable2.getVisiblePartitions();
                result = Lists.newArrayList(p2);
                minTimes = 0;

                olapTable2.getPartition(anyLong);
                result = p2;
                minTimes = 0;

                olapTable2.getDefaultDistributionInfo();
                result = hashDistributionInfo2;
                minTimes = 0;

                olapTable2.isNativeTableOrMaterializedView();
                result = true;
                minTimes = 0;
            }
        };

        expression = OptExpression.create(new LogicalJoinOperator(JoinOperator.INNER_JOIN, predicate),
                OptExpression.create(new LogicalOlapScanOperator(olapTable1, scan1ColumnMap, Maps.newHashMap(),
                        DistributionSpec.createHashDistributionSpec(
                                new HashDistributionDesc(Lists.newArrayList(this.column2.getId()),
                                        HashDistributionDesc.SourceType.LOCAL)), -1, null)),
                OptExpression.create(new LogicalOlapScanOperator(olapTable2, scan2ColumnMap, Maps.newHashMap(),
                        DistributionSpec.createHashDistributionSpec(
                                new HashDistributionDesc(Lists.newArrayList(this.column4.getId()),
                                        HashDistributionDesc.SourceType.LOCAL)), -1, null)));
        optimizer = OptimizerFactory.create(
                OptimizerFactory.mockContext(ctx, columnRefFactory));
        physicalTree = optimizer.optimize(expression, new ColumnRefSet(outputColumns));

        assertEquals(physicalTree.getInputs().get(1).getOp().getOpType(), OperatorType.PHYSICAL_DISTRIBUTION);
        rightOperator = (PhysicalDistributionOperator) physicalTree.getInputs().get(1).getOp();
        assertEquals(rightOperator.getDistributionSpec().getType(), DistributionSpec.DistributionType.BROADCAST);
        rightScan = (PhysicalOlapScanOperator) physicalTree.getInputs().get(1).getInputs().get(0).getOp();
        assertEquals(olapTable1.getId(), rightScan.getTable().getId());
    }

    @Test
    public void testSplitAggregateRuleConstantColumns(@Mocked OlapTable olapTable1) {
        ctx.getSessionVariable().setNewPlanerAggStage(2);

        ColumnRefOperator column1 = columnRefFactory.create("t1", ScalarType.INT, true);
        ColumnRefOperator column2 = columnRefFactory.create("agg", ScalarType.INT, true);

        Map<ColumnRefOperator, Column> scanColumnMap = Maps.newHashMap();
        scanColumnMap.put(column1, new Column());

        List<ScalarOperator> arguments = new ArrayList<>();
        arguments.add(column1);
        arguments.add(new ConstantOperator("const_str", Type.VARCHAR));

        List<ScalarOperator> arrayValues = new ArrayList<>();
        arrayValues.add(new ConstantOperator(1, Type.INT));
        arrayValues.add(new ConstantOperator(2, Type.INT));
        arguments.add(new ArrayOperator(Type.INT, false, arrayValues));

        List<ScalarOperator> functionArguments = new ArrayList<>();
        CallOperator constFunction = new CallOperator("call", Type.INT, functionArguments);
        arguments.add(constFunction);

        List<Type> aggFnArguments = new ArrayList<>();
        aggFnArguments.add(Type.INT);
        aggFnArguments.add(Type.VARCHAR);
        aggFnArguments.add(Type.ARRAY_INT);
        aggFnArguments.add(Type.INT);
        Function fn = new AggregateFunction(new FunctionName("agg_function"), aggFnArguments, Type.INT, Type.INT, false);
        CallOperator aggFunction = new CallOperator("agg", Type.INT, arguments, fn);
        Map<ColumnRefOperator, CallOperator> map = Maps.newHashMap();
        map.put(column2, aggFunction);
        LogicalAggregationOperator aggregationOperator =
                new LogicalAggregationOperator(AggType.GLOBAL, Lists.newArrayList(column1), map);

        OptExpression expression = OptExpression.create(aggregationOperator,
                OptExpression.create(
                        new LogicalOlapScanOperator(olapTable1, scanColumnMap, Maps.newHashMap(), null, -1,
                                null)));

        SplitTwoPhaseAggRule splitTwoPhaseAggRule = SplitTwoPhaseAggRule.getInstance();
        List<OptExpression> list = splitTwoPhaseAggRule.transform(
                expression, OptimizerFactory.mockContext(new ColumnRefFactory()));

        assertEquals(OperatorType.LOGICAL_AGGR, list.get(0).getOp().getOpType());
        LogicalAggregationOperator result = (LogicalAggregationOperator) list.get(0).getOp();

        // The merge stage of the aggregate function should accept 3 constant columns.
        assertEquals(AggType.GLOBAL, result.getType());
        assertEquals(1, result.getAggregations().values().size());
        assertEquals(OperatorType.CALL, result.getAggregations().get(column2).getOpType());
        assertEquals(4, result.getAggregations().get(column2).getChildren().size());
    }
}
