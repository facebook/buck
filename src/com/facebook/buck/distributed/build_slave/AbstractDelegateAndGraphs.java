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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.CachingBuildEngineDelegate;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/** Container class for the build engine delegate and both graphs. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractDelegateAndGraphs {
  public abstract CachingBuildEngineDelegate getCachingBuildEngineDelegate();

  public abstract TargetGraph getTargetGraph();

  public abstract ActionGraphAndResolver getActionGraphAndResolver();
}
