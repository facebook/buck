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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements;
import com.facebook.buck.rules.modern.ModernBuildRule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BiPredicate;

/**
 * RemoteExecutionHelper is used to create remote execution actions for a {@link ModernBuildRule}.
 *
 * <p>The created {@link RemoteExecutionActionInfo} contains all the information necessary for
 * running the rule remotely.
 */
public interface RemoteExecutionHelper {
  /** Returns whether remote execution is supported by this rule. */
  boolean supportsRemoteExecution(ModernBuildRule<?> instance);

  /**
   * Gets all the information needed to run the rule via Remote Execution (inputs merkle tree,
   * action and digest, outputs).
   */
  RemoteExecutionActionInfo prepareRemoteExecution(
      ModernBuildRule<?> rule,
      BiPredicate<Digest, String> requiredDataPredicate,
      WorkerRequirements workerRequirements)
      throws IOException;

  /**
   * The cell path prefix is the path that all remote execution related paths will be relative to.
   */
  Path getCellPathPrefix();
}
