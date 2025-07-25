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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/common/ThreadPoolManager.java

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

package com.starrocks.common;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.starrocks.metric.GaugeMetric;
import com.starrocks.metric.Metric.MetricUnit;
import com.starrocks.metric.MetricLabel;
import com.starrocks.metric.MetricRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ThreadPoolManager is a helper class for construct daemon thread pool with limit thread and memory resource.
 * thread names in thread pool are formatted as poolName-ID, where ID is a unique, sequentially assigned integer.
 * it provide four functions to construct thread pool now.
 * <p>
 * 1. newDaemonCacheThreadPool
 * Wrapper over newCachedThreadPool with additional maxNumThread limit.
 * 2. newDaemonFixedThreadPool
 * Wrapper over newCachedThreadPool with additional blocking queue capacity limit.
 * 3. newDaemonThreadPool
 * Wrapper over ThreadPoolExecutor, user can use it to construct thread pool more flexibly.
 * 4. newDaemonScheduledThreadPool
 * Wrapper over ScheduledThreadPoolExecutor, but without delay task num limit and thread num limit now(NOTICE).
 * <p>
 * All thread pool constructed by ThreadPoolManager will be added to the nameToThreadPoolMap,
 * so the thread pool name in fe must be unique.
 * when all thread pools are constructed, ThreadPoolManager will register some metrics of all thread pool to MetricRepo,
 * so we can know the runtime state for all thread pool by prometheus metrics
 */

public class ThreadPoolManager {

    private static Map<String, ThreadPoolExecutor> nameToThreadPoolMap = Maps.newConcurrentMap();

    private static final String[] POOL_METRIC_TYPES = {"pool_size", "active_thread_num", "task_in_queue",
            "completed_task_count"};

    private static final long KEEP_ALIVE_TIME = 60L;

    private static final ThreadPoolExecutor STATS_CACHE_THREAD_POOL =
            ThreadPoolManager.newDaemonCacheThreadPool(Config.dict_collect_thread_pool_size, "cache-stats",
            false);

    private static final ThreadPoolExecutor STATS_CACHE_THREAD_POOL_FOR_LAKE =
            ThreadPoolManager.newDaemonCacheThreadPool(Config.dict_collect_thread_pool_for_lake_size,
                    "cache-stats-lake", false);

    public static ThreadPoolExecutor getStatsCacheThread() {
        return STATS_CACHE_THREAD_POOL;
    }

    public static ThreadPoolExecutor getStatsCacheThreadPoolForLake() {
        return STATS_CACHE_THREAD_POOL_FOR_LAKE;
    }

    public static void registerAllThreadPoolMetric() {
        for (Map.Entry<String, ThreadPoolExecutor> entry : nameToThreadPoolMap.entrySet()) {
            registerThreadPoolMetric(entry.getKey(), entry.getValue());
        }
        nameToThreadPoolMap.clear();
    }

    public static void registerThreadPoolMetric(String poolName, ThreadPoolExecutor threadPool) {
        for (String poolMetricType : POOL_METRIC_TYPES) {
            GaugeMetric<Integer> gauge =
                    new GaugeMetric<Integer>("thread_pool", MetricUnit.NOUNIT, "thread_pool statistics") {
                        @Override
                        public Integer getValue() {
                            String metricType = this.getLabels().get(1).getValue();
                            switch (metricType) {
                                case "pool_size":
                                    return threadPool.getPoolSize();
                                case "active_thread_num":
                                    return threadPool.getActiveCount();
                                case "task_in_queue":
                                    return threadPool.getQueue().size();
                                case "completed_task_count":
                                    return (int) threadPool.getCompletedTaskCount();
                                default:
                                    return 0;
                            }
                        }
                    };
            gauge.addLabel(new MetricLabel("name", poolName))
                    .addLabel(new MetricLabel("type", poolMetricType));
            MetricRepo.addMetric(gauge);
        }
    }

    public static ThreadPoolExecutor newDaemonCacheThreadPool(int maxNumThread, String poolName,
                                                              boolean needRegisterMetric) {
        return newDaemonThreadPool(0, maxNumThread, KEEP_ALIVE_TIME, TimeUnit.SECONDS, new SynchronousQueue(),
                new LogDiscardPolicy(poolName), poolName, needRegisterMetric);
    }

    public static ThreadPoolExecutor newDaemonCacheThreadPool(int maxNumThread, int queueSize, String poolName,
            boolean needRegisterMetric) {
        return newDaemonThreadPool(0, maxNumThread, KEEP_ALIVE_TIME, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                new BlockedPolicy(poolName, 5), poolName, needRegisterMetric);
    }

    public static ThreadPoolExecutor newDaemonFixedThreadPool(int numThread, int queueSize, String poolName,
                                                              boolean needRegisterMetric) {
        return newDaemonThreadPool(numThread, numThread, KEEP_ALIVE_TIME, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                new BlockedPolicy(poolName, 60), poolName, needRegisterMetric);
    }

    public static PriorityThreadPoolExecutor newDaemonFixedPriorityThreadPool(int numThread, int queueSize,
            String poolName, boolean needRegisterMetric) {
        ThreadFactory threadFactory = namedThreadFactory(poolName);
        PriorityThreadPoolExecutor threadPool = new PriorityThreadPoolExecutor(numThread, numThread, 0,
                TimeUnit.SECONDS, new PriorityBlockingQueue<>(queueSize), threadFactory,
                new BlockedPolicy(poolName, 60));
        if (needRegisterMetric) {
            nameToThreadPoolMap.put(poolName, threadPool);
        }
        return threadPool;
    }

    public static ThreadPoolExecutor newDaemonThreadPool(int corePoolSize,
                                                         int maximumPoolSize,
                                                         long keepAliveTime,
                                                         TimeUnit unit,
                                                         BlockingQueue<Runnable> workQueue,
                                                         RejectedExecutionHandler handler,
                                                         String poolName,
                                                         boolean needRegisterMetric) {
        ThreadFactory threadFactory = namedThreadFactory(poolName);
        ThreadPoolExecutor threadPool =
                new ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory,
                        handler);
        if (needRegisterMetric) {
            nameToThreadPoolMap.put(poolName, threadPool);
        }
        return threadPool;
    }

    // Now, we have no delay task num limit and thread num limit in ScheduledThreadPoolExecutor,
    // so it may cause oom when there are too many delay tasks or threads in ScheduledThreadPoolExecutor
    // Please use this api only for scheduling short task at fix rate.
    public static ScheduledThreadPoolExecutor newDaemonScheduledThreadPool(int corePoolSize, String poolName,
                                                                           boolean needRegisterMetric) {
        ThreadFactory threadFactory = namedThreadFactory(poolName);
        ScheduledThreadPoolExecutor scheduledThreadPoolExecutor =
                new ScheduledThreadPoolExecutor(corePoolSize, threadFactory);
        if (needRegisterMetric) {
            nameToThreadPoolMap.put(poolName, scheduledThreadPoolExecutor);
        }
        return scheduledThreadPoolExecutor;
    }

    /**
     * Create a thread factory that names threads with a prefix and also sets the threads to daemon.
     */
    private static ThreadFactory namedThreadFactory(String poolName) {
        return new ThreadFactoryBuilder().setDaemon(true).setNameFormat(poolName + "-%d").build();
    }

    /**
     * A handler for rejected task that discards and log it, used for cached thread pool
     */
    static class LogDiscardPolicy implements RejectedExecutionHandler {

        private static final Logger LOG = LogManager.getLogger(LogDiscardPolicy.class);

        private String threadPoolName;

        public LogDiscardPolicy(String threadPoolName) {
            this.threadPoolName = threadPoolName;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            LOG.warn("Task " + r.toString() + " rejected from " + threadPoolName + " " + executor.toString());
        }
    }

    /**
     * A handler for rejected task that try to be blocked until the pool enqueue task succeed or timeout,
     * used for fixed thread pool throw RejectedExecutionException if timeout or catch InterruptedException
     */
    static class BlockedPolicy implements RejectedExecutionHandler {

        private static final Logger LOG = LogManager.getLogger(BlockedPolicy.class);

        private String threadPoolName;

        private int timeoutSeconds;

        public BlockedPolicy(String threadPoolName, int timeoutSeconds) {
            this.threadPoolName = threadPoolName;
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            String msg = "Task " + r.toString() + " wait to enqueue in " + threadPoolName + " "
                    + executor.toString() + " failed";
            try {
                if (!executor.getQueue().offer(r, timeoutSeconds, TimeUnit.SECONDS)) {
                    throw new RejectedExecutionException(msg);
                }
            } catch (InterruptedException e) {
                throw new RejectedExecutionException(msg);
            }
        }
    }

    public static void setCacheThreadPoolSize(ThreadPoolExecutor executor, int newMaxPoolSize) {
        if (executor.getCorePoolSize() > 0) {
            // skip for non-cache thread pool
            return;
        }
        int maxPoolSize = executor.getMaximumPoolSize();
        if (newMaxPoolSize <= 0 || maxPoolSize == newMaxPoolSize) { // no change
            return;
        }
        executor.setMaximumPoolSize(newMaxPoolSize);
    }

    public static void setFixedThreadPoolSize(ThreadPoolExecutor executor, int poolSize) {
        int coreSize = executor.getCorePoolSize();
        if (coreSize == poolSize) { // no change
            return;
        }
        if (coreSize < poolSize) {
            // increase the pool size, set the `MaximumPoolSize` first and then the `CoreSize`
            executor.setMaximumPoolSize(poolSize);
            executor.setCorePoolSize(poolSize);
        } else {
            // decrease the pool size, set `CoreSize` first and then the `MaximumPoolSize`
            executor.setCorePoolSize(poolSize);
            executor.setMaximumPoolSize(poolSize);
        }
    }

    /**
     * Calculate the number of CPU cores available on the machine.
     *
     * @return The number of CPU cores available.
     */
    public static int cpuCores() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * Use at most 3/4 to execute cpu-intensive background tasks
     */
    public static int cpuIntensiveThreadPoolSize() {
        return Integer.max(2, cpuCores() * 3 / 4);
    }
}

