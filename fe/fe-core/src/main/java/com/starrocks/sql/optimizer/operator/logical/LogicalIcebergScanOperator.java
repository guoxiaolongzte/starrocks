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

package com.starrocks.sql.optimizer.operator.logical;

import com.google.common.base.Preconditions;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.catalog.Table;
import com.starrocks.connector.TableVersionRange;
import com.starrocks.connector.iceberg.IcebergDeleteSchema;
import com.starrocks.connector.iceberg.IcebergMORParams;
import com.starrocks.connector.iceberg.IcebergTableMORParams;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.OperatorVisitor;
import com.starrocks.sql.optimizer.operator.ScanOperatorPredicates;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class LogicalIcebergScanOperator extends LogicalScanOperator {
    private ScanOperatorPredicates predicates = new ScanOperatorPredicates();
    private boolean hasUnknownColumn = true;

    // record if this scan is derived from IcebergEqualityDeleteRewriteRule.
    private boolean fromEqDeleteRewriteRule;

    private Set<IcebergDeleteSchema> deleteSchemas = new HashSet<>();

    // Mainly used for table with iceberg equality delete files. Record full iceberg mor params in the table,
    // used for the first build to associate multiple scan nodes RemoteFileInfoSource.
    private IcebergTableMORParams tableFullMORParams = IcebergTableMORParams.EMPTY;

    // Mainly used for table with iceberg equality delete files.
    // Marking this split scan node type after IcebergEqualityDeleteRewriteRule rewriting.
    private IcebergMORParams morParam = IcebergMORParams.EMPTY;

    public LogicalIcebergScanOperator(Table table,
                                      Map<ColumnRefOperator, Column> colRefToColumnMetaMap,
                                      Map<Column, ColumnRefOperator> columnMetaToColRefMap,
                                      long limit,
                                      ScalarOperator predicate) {
        this(table, colRefToColumnMetaMap, columnMetaToColRefMap, limit, predicate, TableVersionRange.empty());
    }

    public LogicalIcebergScanOperator(Table table,
                                      Map<ColumnRefOperator, Column> colRefToColumnMetaMap,
                                      Map<Column, ColumnRefOperator> columnMetaToColRefMap,
                                      long limit,
                                      ScalarOperator predicate,
                                      TableVersionRange versionRange) {
        super(OperatorType.LOGICAL_ICEBERG_SCAN,
                table,
                colRefToColumnMetaMap,
                columnMetaToColRefMap,
                limit,
                predicate, null, versionRange);

        Preconditions.checkState(table instanceof IcebergTable);
        IcebergTable icebergTable = (IcebergTable) table;
        partitionColumns.addAll(icebergTable.getPartitionColumns().stream().map(Column::getName).collect(Collectors.toList()));
    }

    private LogicalIcebergScanOperator() {
        super(OperatorType.LOGICAL_ICEBERG_SCAN);
    }

    @Override
    public ScanOperatorPredicates getScanOperatorPredicates() {
        return this.predicates;
    }

    @Override
    public void setScanOperatorPredicates(ScanOperatorPredicates predicates) {
        this.predicates = predicates;
    }

    public boolean isFromEqDeleteRewriteRule() {
        return fromEqDeleteRewriteRule;
    }

    public void setFromEqDeleteRewriteRule(boolean fromEqDeleteRewriteRule) {
        this.fromEqDeleteRewriteRule = fromEqDeleteRewriteRule;
    }

    public Set<IcebergDeleteSchema> getDeleteSchemas() {
        return deleteSchemas;
    }

    public void setDeleteSchemas(Set<IcebergDeleteSchema> deleteSchemas) {
        this.deleteSchemas = deleteSchemas;
    }

    public IcebergTableMORParams getTableFullMORParams() {
        return tableFullMORParams;
    }

    public void setTableFullMORParams(IcebergTableMORParams tableFullMORParams) {
        this.tableFullMORParams = tableFullMORParams;
    }

    public IcebergMORParams getMORParam() {
        return morParam;
    }

    public void setMORParam(IcebergMORParams morParam) {
        this.morParam = morParam;
    }

    public boolean hasUnknownColumn() {
        return hasUnknownColumn;
    }

    public void setHasUnknownColumn(boolean hasUnknownColumn) {
        this.hasUnknownColumn = hasUnknownColumn;
    }

    @Override
    public <R, C> R accept(OperatorVisitor<R, C> visitor, C context) {
        return visitor.visitLogicalIcebergScan(this, context);
    }

    public static class Builder
            extends LogicalScanOperator.Builder<LogicalIcebergScanOperator, LogicalIcebergScanOperator.Builder> {

        @Override
        protected LogicalIcebergScanOperator newInstance() {
            return new LogicalIcebergScanOperator();
        }

        @Override
        public LogicalIcebergScanOperator.Builder withOperator(LogicalIcebergScanOperator scanOperator) {
            super.withOperator(scanOperator);
            builder.predicates = scanOperator.predicates.clone();
            builder.morParam = scanOperator.morParam;
            builder.tableFullMORParams = scanOperator.tableFullMORParams;
            return this;
        }
    }
}
