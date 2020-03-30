/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.remoteexecution.config;

import com.facebook.buck.io.filesystem.PathMatcher;
import com.google.common.collect.ImmutableSet;
import java.util.OptionalLong;

/** Configuration for the remote execution strategy. */
public interface RemoteExecutionStrategyConfig {
  int getThreads();

  int getMaxConcurrentActionComputations();

  int getMaxConcurrentExecutions();

  int getMaxConcurrentResultHandling();

  int getOutputMaterializationThreads();

  int getMaxConcurrentPendingUploads();

  boolean isLocalFallbackEnabled();

  boolean isLocalFallbackDisabledOnCorruptedArtifacts();

  boolean isLocalFallbackEnabledForCompletedAction();

  OptionalLong maxInputSizeBytes();

  OptionalLong largeBlobSizeBytes();

  String getWorkerRequirementsFilename();

  boolean tryLargerWorkerOnOom();

  ImmutableSet<PathMatcher> getIgnorePaths();
}
