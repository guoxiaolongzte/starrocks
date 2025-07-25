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
//
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/gensrc/thrift/InternalService.thrift

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

namespace cpp starrocks
namespace java com.starrocks.thrift

include "Status.thrift"
include "Types.thrift"
include "Exprs.thrift"
include "Descriptors.thrift"
include "PlanNodes.thrift"
include "Planner.thrift"
include "DataSinks.thrift"
include "Data.thrift"
include "RuntimeProfile.thrift"
include "WorkGroup.thrift"
include "RuntimeFilter.thrift"
include "CloudConfiguration.thrift"

// constants for function version
enum TFunctionVersion {
    RUNTIME_FILTER_SERIALIZE_VERSION_2 = 7,
    RUNTIME_FILTER_SERIALIZE_VERSION_3 = 8,
}

enum TQueryType {
    SELECT,
    LOAD,
    EXTERNAL
}

enum TLoadJobType {
    BROKER,
    SPARK,
    INSERT_QUERY,
    INSERT_VALUES,
    STREAM_LOAD,
    ROUTINE_LOAD,
}

enum TErrorHubType {
    MYSQL,
    BROKER,
    NULL_TYPE
}

struct TMysqlErrorHubInfo {
    1: required string host;
    2: required i32 port;
    3: required string user;
    4: required string passwd;
    5: required string db;
    6: required string table;
}

struct TBrokerErrorHubInfo {
    1: required Types.TNetworkAddress broker_addr;
    2: required string path;
    3: required map<string, string> prop;
}

struct TLoadErrorHubInfo {
    1: required TErrorHubType type = TErrorHubType.NULL_TYPE;
    2: optional TMysqlErrorHubInfo mysql_info;
    3: optional TBrokerErrorHubInfo broker_info;
}

enum TPipelineProfileLevel {
  MERGE = 1;
  DETAIL = 2;
}

enum TSpillMode {
  AUTO,
  FORCE,
  NONE,
  RANDOM,
}

enum TSpillableOperatorType {
  HASH_JOIN = 0;
  AGG = 1;
  AGG_DISTINCT = 2;
  SORT = 3;
  NL_JOIN = 4;
  MULTI_CAST_LOCAL_EXCHANGE = 5;
}

enum TTabletInternalParallelMode {
  AUTO,
  FORCE_SPLIT
}

enum TOverflowMode {
  OUTPUT_NULL = 0;
  REPORT_ERROR = 1;
}

enum TTimeUnit {
    NANOSECOND = 0;
    MICROSECOND = 1;
    MILLISECOND = 2;
    SECOND = 3;
    MINUTE = 4;
}

struct TQueryQueueOptions {
  1: optional bool enable_global_query_queue;
  2: optional bool enable_group_level_query_queue;
}

struct TSpillToRemoteStorageOptions {
  1: optional list<string> remote_storage_paths;
  2: optional CloudConfiguration.TCloudConfiguration remote_storage_conf;
  3: optional bool disable_spill_to_local_disk;
}

// spill options
struct TSpillOptions {
  1: optional i32 spill_mem_table_size;
  2: optional i32 spill_mem_table_num;
  3: optional double spill_mem_limit_threshold;
  4: optional i64 spill_operator_min_bytes;
  5: optional i64 spill_operator_max_bytes;
  6: optional i32 spill_encode_level;
  7: optional i64 spill_revocable_max_bytes;
  8: optional bool spill_enable_direct_io;
  9: optional bool spill_enable_compaction;
  // only used in spill_mode="random"
  // probability of triggering operator spill
  // (0.0,1.0)
  10: optional double spill_rand_ratio;
  11: optional TSpillMode spill_mode;
  // used to identify which operators allow spill, only meaningful when enable_spill=true
  12: optional i64 spillable_operator_mask;
  13: optional bool enable_agg_spill_preaggregation;

  21: optional bool enable_spill_to_remote_storage;
  22: optional TSpillToRemoteStorageOptions spill_to_remote_storage_options;
  23: optional bool enable_spill_buffer_read;
  24: optional i64 max_spill_read_buffer_bytes_per_driver;
  25: optional i64 spill_hash_join_probe_op_max_bytes;

  26: optional bool spill_partitionwise_agg;
  27: optional i32 spill_partitionwise_agg_partition_num;
  28: optional bool spill_partitionwise_agg_skew_elimination;
}

// Query options with their respective defaults
struct TQueryOptions {
  2: optional i32 max_errors = 0
  4: optional i32 batch_size = 0

  12: optional i64 mem_limit = 2147483648
  13: optional bool abort_on_default_limit_exceeded = 0
  14: optional i32 query_timeout = 3600
  15: optional bool enable_profile = 0

  18: optional TQueryType query_type = TQueryType.SELECT

  // if this is a query option for LOAD, load_mem_limit should be set to limit the mem comsuption
  // of load channel.
  28: optional i64 load_mem_limit = 0;
  // see BE config `starrocks_max_scan_key_num` for details
  // if set, this will overwrite the BE config.
  29: optional i32 max_scan_key_num;
  // see BE config `max_pushdown_conditions_per_column` for details
  // if set, this will overwrite the BE config.
  30: optional i32 max_pushdown_conditions_per_column
  // whether enable spill to disk
  31: optional bool enable_spill = false;

  50: optional Types.TCompressionType transmission_compression_type;

  51: optional i64 runtime_join_filter_pushdown_limit;
  // Timeout in ms to wait until runtime filters are arrived.
  52: optional i32 runtime_filter_wait_timeout_ms = 200;
  // Timeout in ms to send runtime filter across nodes.
  53: optional i32 runtime_filter_send_timeout_ms = 400;
  // For pipeline query engine
  54: optional i32 pipeline_dop;
  // For pipeline query engine
  55: optional TPipelineProfileLevel pipeline_profile_level;
  // For load degree of parallel
  56: optional i32 load_dop;
  57: optional i64 runtime_filter_scan_wait_time_ms;

  58: optional i64 query_mem_limit;

  59: optional bool enable_tablet_internal_parallel;

  60: optional i32 query_delivery_timeout;

  61: optional bool enable_query_debug_trace;

  62: optional Types.TCompressionType load_transmission_compression_type;

  63: optional TTabletInternalParallelMode tablet_internal_parallel_mode;

  64: optional TLoadJobType load_job_type

  66: optional bool enable_scan_datacache;

  67: optional bool enable_pipeline_query_statistic = false;

  68: optional i32 transmission_encode_level;

  69: optional bool enable_populate_datacache;

  70: optional bool allow_throw_exception = 0;

  71: optional bool hudi_mor_force_jni_reader;

  72: optional i64 rpc_http_min_size;

  // Deprecated
  // some experimental parameter for spill
  // TODO: remove in 3.4.x
  73: optional i32 spill_mem_table_size;
  74: optional i32 spill_mem_table_num;
  75: optional double spill_mem_limit_threshold;
  76: optional i64 spill_operator_min_bytes;
  77: optional i64 spill_operator_max_bytes;
  78: optional i32 spill_encode_level;
  79: optional i64 spill_revocable_max_bytes;
  80: optional bool spill_enable_direct_io;
  81: optional double spill_rand_ratio;
  85: optional TSpillMode spill_mode;

  82: optional TSpillOptions spill_options;

  86: optional i32 io_tasks_per_scan_operator = 4;
  87: optional i32 connector_io_tasks_per_scan_operator = 16;
  88: optional double runtime_filter_early_return_selectivity = 0.05;
  89: optional bool enable_dynamic_prune_scan_range = true;

  90: optional i64 log_rejected_record_num = 0;

  91: optional bool use_page_cache;

  92: optional bool enable_connector_adaptive_io_tasks = true;
  93: optional i32 connector_io_tasks_slow_io_latency_ms = 50;
  94: optional double scan_use_query_mem_ratio = 0.25;
  95: optional double connector_scan_use_query_mem_ratio = 0.3;
  // Deprecated
  // used to identify which operators allow spill, only meaningful when enable_spill=true
  96: optional i64 spillable_operator_mask;
  // used to judge whether the profile need to report to FE, only meaningful when enable_profile=true
  97: optional i64 load_profile_collect_second;

  100: optional i64 group_concat_max_len = 1024;
  101: optional i64 runtime_profile_report_interval = 30;

  102: optional bool enable_collect_table_level_scan_stats;

  103: optional i32 interleaving_group_size;

  104: optional TOverflowMode overflow_mode = TOverflowMode.OUTPUT_NULL;
  105: optional bool use_column_pool = true; // Deprecated
  // Deprecated
  106: optional bool enable_agg_spill_preaggregation;
  107: optional i64 global_runtime_filter_build_max_size;
  108: optional i64 runtime_filter_rpc_http_min_size;

  109: optional i64 big_query_profile_threshold = 0;

  110: optional TQueryQueueOptions query_queue_options;

  111: optional bool enable_file_metacache;

  112: optional bool enable_pipeline_level_shuffle;
  113: optional bool enable_hyperscan_vec;

  114: optional i32 jit_level = 1;

  115: optional TTimeUnit big_query_profile_threshold_unit = TTimeUnit.SECOND;

  116: optional string sql_dialect;

  119: optional bool enable_result_sink_accumulate;
  120: optional bool enable_connector_split_io_tasks = false;
  121: optional i64 connector_max_split_size = 0;
  122: optional bool enable_connector_sink_writer_scaling = true;

  130: optional bool enable_wait_dependent_event = false;

  131: optional bool orc_use_column_names = false;

  132: optional bool enable_datacache_async_populate_mode;
  133: optional bool enable_datacache_io_adaptor;
  134: optional i32 datacache_priority;
  135: optional i64 datacache_ttl_seconds;
  136: optional bool enable_cache_select;
  137: optional i64 datacache_sharing_work_period;
  138: optional bool enable_file_pagecache;

  140: optional string catalog;

  141: optional i32 datacache_evict_probability;

  142: optional bool enable_pipeline_event_scheduler;

  150: optional map<string, string> ann_params;
  151: optional double pq_refine_factor;
  152: optional double k_factor;

  160: optional bool enable_join_runtime_filter_pushdown;
  161: optional bool enable_join_runtime_bitset_filter;
  162: optional bool enable_hash_join_range_direct_mapping_opt

  170: optional bool enable_parquet_reader_bloom_filter;
  171: optional bool enable_parquet_reader_page_index;
  
  180: optional bool lower_upper_support_utf8;

  190: optional i64 column_view_concat_rows_limit;
  191: optional i64 column_view_concat_bytes_limit;
}

// A scan range plus the parameters needed to execute that scan.
struct TScanRangeParams {
  1: required PlanNodes.TScanRange scan_range
  2: optional i32 volume_id = -1
  // if this is just a placeholder and no `scan_range` data in it.
  3: optional bool empty = false;
  // if there is no more scan range from this scan node.
  4: optional bool has_more = false;
}

struct TExecDebugOption {
  1: optional Types.TPlanNodeId debug_node_id
  2: optional PlanNodes.TDebugAction debug_action
  3: optional i32 value
}

// Parameters for a single execution instance of a particular TPlanFragment
// TODO: for range partitioning, we also need to specify the range boundaries
struct TPlanFragmentExecParams {
  // a globally unique id assigned to the entire query
  1: required Types.TUniqueId query_id

  // a globally unique id assigned to this particular execution instance of
  // a TPlanFragment
  2: required Types.TUniqueId fragment_instance_id

  // initial scan ranges for each scan node in TPlanFragment.plan_tree
  3: required map<Types.TPlanNodeId, list<TScanRangeParams>> per_node_scan_ranges

  // number of senders for ExchangeNodes contained in TPlanFragment.plan_tree;
  // needed to create a DataStreamRecvr
  4: required map<Types.TPlanNodeId, i32> per_exch_num_senders

  // Output destinations, one per output partition.
  // The partitioning of the output is specified by
  // TPlanFragment.output_sink.output_partition.
  // The number of output partitions is destinations.size().
  5: list<DataSinks.TPlanFragmentDestination> destinations

  // Id of this fragment in its role as a sender.
  9: optional i32 sender_id
  10: optional i32 num_senders
  11: optional bool send_query_statistics_with_every_batch
  12: optional bool use_vectorized // whether to use vectorized query engine

  // Global runtime filters
  50: optional RuntimeFilter.TRuntimeFilterParams runtime_filter_params
  51: optional i32 instances_number
  // To enable pass through chunks between sink/exchange if they are in the same process.
  52: optional bool enable_exchange_pass_through

  53: optional map<Types.TPlanNodeId, map<i32, list<TScanRangeParams>>> node_to_per_driver_seq_scan_ranges

  54: optional bool enable_exchange_perf

  70: optional i32 pipeline_sink_dop

  73: optional bool report_when_finish

  // Debug options: perform some action in a particular phase of a particular node
  74: optional list<TExecDebugOption> exec_debug_options
}

// Global query parameters assigned by the coordinator.
struct TQueryGlobals {
  // String containing a timestamp set as the current time.
  // Format is yyyy-MM-dd HH:mm:ss
  1: required string now_string

  // To support timezone in StarRocks. timestamp_ms is the millisecond uinix timestamp for
  // this query to calculate time zone relative function
  2: optional i64 timestamp_ms

  // time_zone is the timezone this query used.
  // If this value is set, BE will ignore now_string
  3: optional string time_zone

  // Added by StarRocks
  // Required by function 'last_query_id'.
  30: optional string last_query_id

  31: optional i64 timestamp_us

  32: optional i64 connector_scan_node_number
}


// Service Protocol Details

enum InternalServiceVersion {
  V1
}

struct TAdaptiveDopParam {
  1: optional i64 max_block_rows_per_driver_seq
  2: optional i64 max_output_amplification_factor
}

struct TPredicateTreeParams {
  1: optional bool enable_or
  2: optional bool enable_show_in_profile
}

// ExecPlanFragment

struct TExecPlanFragmentParams {
  1: required InternalServiceVersion protocol_version

  // required in V1
  2: optional Planner.TPlanFragment fragment

  // required in V1
  3: optional Descriptors.TDescriptorTable desc_tbl

  // required in V1
  4: optional TPlanFragmentExecParams params

  // Initiating coordinator.
  // TODO: determine whether we can get this somehow via the Thrift rpc mechanism.
  // required in V1
  5: optional Types.TNetworkAddress coord

  // backend number assigned by coord to identify backend
  // required in V1
  6: optional i32 backend_num

  // Global query parameters assigned by coordinator.
  // required in V1
  7: optional TQueryGlobals query_globals

  // options for the query
  // required in V1
  8: optional TQueryOptions query_options

  // Whether reportd when the backend fails
  // required in V1
  9: optional bool enable_profile

  // required in V1
  10: optional Types.TResourceInfo resource_info

  // load job related
  11: optional string import_label
  12: optional string db_name
  13: optional i64 load_job_id
  14: optional TLoadErrorHubInfo load_error_hub_info

  50: optional bool is_pipeline
  51: optional i32 pipeline_dop
  52: optional map<Types.TPlanNodeId, i32> per_scan_node_dop

  53: optional WorkGroup.TWorkGroup workgroup
  54: optional bool enable_resource_group
  55: optional i32 func_version

  // Sharing data between drivers of same scan operator
  56: optional bool enable_shared_scan

  57: optional bool is_stream_pipeline

  58: optional TAdaptiveDopParam adaptive_dop_param
  59: optional i32 group_execution_scan_dop

  60: optional TPredicateTreeParams pred_tree_params

  61: optional list<i32> exec_stats_node_ids;
}

struct TExecPlanFragmentResult {
  // required in V1
  1: optional Status.TStatus status
}

struct TExecBatchPlanFragmentsParams {
  // required in V1
  1: optional TExecPlanFragmentParams common_param
  // required in V1
  2: optional list<TExecPlanFragmentParams> unique_param_per_instance
}

// CancelPlanFragment
struct TCancelPlanFragmentParams {
  1: required InternalServiceVersion protocol_version

  // required in V1
  2: optional Types.TUniqueId fragment_instance_id
}

struct TCancelPlanFragmentResult {
  // required in V1
  1: optional Status.TStatus status
}


// TransmitData
struct TTransmitDataParams {
  1: required InternalServiceVersion protocol_version

  // required in V1
  2: optional Types.TUniqueId dest_fragment_instance_id

  // for debugging purposes; currently ignored
  //3: optional Types.TUniqueId src_fragment_instance_id

  // required in V1
  4: optional Types.TPlanNodeId dest_node_id

  // required in V1
  5: optional Data.TRowBatch row_batch

  // if set to true, indicates that no more row batches will be sent
  // for this dest_node_id
  6: optional bool eos

  7: optional i32 be_number
  8: optional i64 packet_seq

  // Id of this fragment in its role as a sender.
  9: optional i32 sender_id
}

struct TTransmitDataResult {
  // required in V1
  1: optional Status.TStatus status
  2: optional i64 packet_seq
  3: optional Types.TUniqueId dest_fragment_instance_id
  4: optional Types.TPlanNodeId dest_node_id
}

struct TFetchDataParams {
  1: required InternalServiceVersion protocol_version
  // required in V1
  // query id which want to fetch data
  2: required Types.TUniqueId fragment_instance_id
}

struct TFetchDataResult {
    // result batch
    1: required Data.TResultBatch result_batch
    // end of stream flag
    2: required bool eos
    // packet num used check lost of packet
    3: required i32 packet_num
    // Operation result
    4: optional Status.TStatus status
}

struct TCondition {
    1:  required string column_name
    2:  required string condition_op
    3:  required list<string> condition_values
    // whether this condition only used to filter index, not filter chunk row in storage engine
    20: optional bool is_index_filter_only
}

struct TExportStatusResult {
    1: required Status.TStatus status
    2: required Types.TExportState state
    3: optional list<string> files
}

struct TGetFileSchemaRequest {
  1: required PlanNodes.TScanRange scan_range
  2: optional i32 volume_id = -1
}
