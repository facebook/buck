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
package com.facebook.buck.cli;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.google.common.util.concurrent.ListenableFuture;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

@BuckStyleImmutable
@Immutable(builder = false, copy = false)
abstract class AbstractAsyncJobStateAndCells {
  @Value.Parameter
  public abstract ListenableFuture<BuildJobState> getAsyncJobState();

  @Value.Parameter
  public abstract DistBuildCellIndexer getDistBuildCellIndexer();
}
