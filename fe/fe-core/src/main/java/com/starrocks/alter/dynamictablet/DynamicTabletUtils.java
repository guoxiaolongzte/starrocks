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

package com.starrocks.alter.dynamictablet;

public class DynamicTabletUtils {

    public static boolean isPowerOfTwo(long n) {
        return n > 1 && (n & (n - 1)) == 0;
    }

    public static int calcSplitCount(long dataSize, long splitSize) {
        int splitCount = 1;
        while (dataSize > splitCount) {
            splitCount *= 2;
            dataSize /= splitCount;
        }
        return splitCount;
    }
}
