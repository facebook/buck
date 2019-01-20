/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.distributed.thrift.WorkUnit;
import java.util.List;

/** Defines a build targets queue to be used in distributed builds. */
public interface BuildTargetsQueue {
  boolean hasReadyZeroDependencyNodes();

  List<WorkUnit> dequeueZeroDependencyNodes(List<String> finishedNodes, int maxUnitsOfWork);

  boolean haveMostBuildRulesFinished();

  CoordinatorBuildProgress getBuildProgress();

  int getSafeApproxOfRemainingWorkUnitsCount();

  DistributableBuildGraph getDistributableBuildGraph();
}
