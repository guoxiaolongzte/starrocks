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

package com.starrocks.persist;

import com.google.gson.annotations.SerializedName;
import com.starrocks.common.io.Text;
import com.starrocks.common.io.Writable;
import com.starrocks.persist.gson.GsonUtils;

import java.io.DataInput;
import java.io.IOException;
import java.util.List;

/*
 * For serialize "replace temp partition" operation log
 */
public class ReplacePartitionOperationLog implements Writable {

    @SerializedName(value = "dbId")
    private long dbId;
    @SerializedName(value = "tblId")
    private long tblId;
    @SerializedName(value = "partitions")
    private List<String> partitions;
    @SerializedName(value = "tempPartitions")
    private List<String> tempPartitions;
    @SerializedName(value = "strictRange")
    private boolean strictRange;
    @SerializedName(value = "useTempPartitionName")
    private boolean useTempPartitionName;
    @SerializedName(value = "unPartitionedTable")
    private boolean unPartitionedTable;

    public ReplacePartitionOperationLog(long dbId, long tblId, List<String> partitionNames,
                                        List<String> tempPartitonNames, boolean strictRange,
                                        boolean useTempPartitionName, boolean unPartitionedTable) {
        this.dbId = dbId;
        this.tblId = tblId;
        this.partitions = partitionNames;
        this.tempPartitions = tempPartitonNames;
        this.strictRange = strictRange;
        this.useTempPartitionName = useTempPartitionName;
        this.unPartitionedTable = unPartitionedTable;
    }

    public ReplacePartitionOperationLog(long dbId, long tblId, List<String> partitionNames,
                                        List<String> tempPartitonNames, boolean strictRange,
                                        boolean useTempPartitionName) {
        this(dbId, tblId, partitionNames, tempPartitonNames, strictRange, useTempPartitionName, false);
    }

    public long getDbId() {
        return dbId;
    }

    public long getTblId() {
        return tblId;
    }

    public List<String> getPartitions() {
        return partitions;
    }

    public List<String> getTempPartitions() {
        return tempPartitions;
    }

    public boolean isStrictRange() {
        return strictRange;
    }

    public boolean useTempPartitionName() {
        return useTempPartitionName;
    }

    public boolean isUnPartitionedTable() {
        return unPartitionedTable;
    }

    public static ReplacePartitionOperationLog read(DataInput in) throws IOException {
        String json = Text.readString(in);
        return GsonUtils.GSON.fromJson(json, ReplacePartitionOperationLog.class);
    }


}
