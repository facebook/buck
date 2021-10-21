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

package com.facebook.buck.remoteexecution;

import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.Platform.Property;
import com.facebook.buck.remoteexecution.proto.ActionHistoryInfo;
import com.facebook.buck.util.types.Pair;

/** Provides rule's RE worker requirements based on JSON file */
public final class OOMWorkerRequirementsProvider implements WorkerRequirementsProvider {

  public static final Platform PLATFORM_DEFAULT =
      Platform.newBuilder()
          .addProperties(
              Property.newBuilder().setName("platform").setValue("linux-remote-execution").build())
          .build();
  public static final ActionHistoryInfo RETRY_ON_OOM_DEFAULT =
      ActionHistoryInfo.newBuilder().setDisableRetryOnOom(false).build();

  public static final ActionHistoryInfo DONT_RETRY_ON_OOM_DEFAULT =
      ActionHistoryInfo.newBuilder().setDisableRetryOnOom(true).build();

  private final boolean tryLargerWorkerOnOom;

  public OOMWorkerRequirementsProvider(boolean tryLargerWorkerOnOom) {
    this.tryLargerWorkerOnOom = tryLargerWorkerOnOom;
  }

  /** Resolve rule's worker requirements (platform + action history info) */
  @Override
  public Pair<Platform, ActionHistoryInfo> resolveRequirements() {
    return tryLargerWorkerOnOom
        ? new Pair<>(PLATFORM_DEFAULT, RETRY_ON_OOM_DEFAULT)
        : new Pair<>(PLATFORM_DEFAULT, DONT_RETRY_ON_OOM_DEFAULT);
  }
}
