/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.distributed;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.distributed.thrift.CacheRateStats;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractBuildSlaveFinishedStatus {

  abstract StampedeId getStampedeId();

  abstract RunId getRunId();

  abstract int getTotalRulesCount();

  abstract int getRulesStartedCount();

  abstract int getRulesFinishedCount();

  abstract int getRulesSuccessCount();

  abstract int getRulesFailureCount();

  abstract int getExitCode();

  abstract CacheRateStats getCacheRateStats();

  abstract FileMaterializationStats getFileMaterializationStats();

  abstract BuckConfig getRemoteBuckConfig();

  abstract DistBuildSlaveTimingStats getTimingStats();
}
