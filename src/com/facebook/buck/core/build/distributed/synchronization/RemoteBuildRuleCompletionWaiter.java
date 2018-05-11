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
package com.facebook.buck.core.build.distributed.synchronization;

import com.facebook.buck.rules.BuildRule;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Used by a local build to wait for remote completion of build rules (if there is a remote build)
 */
public interface RemoteBuildRuleCompletionWaiter {

  /**
   * Local Buck builds will never wait for remote completion of rule before building locally.
   * Stampede builds always wait if always_wait_for_remote_build_before_proceeding_locally=true, and
   * will also wait if set to false but the build rule has already started building remotely.
   *
   * @param buildTarget
   * @return
   */
  boolean shouldWaitForRemoteCompletionOfBuildRule(String buildTarget);

  /**
   * When performing a remote/distributed build, Future will get set once the given build target has
   * finished building remotely. For a non remote build, this operation is a no-op and the Future
   * will return immediately.
   *
   * @return Future that gets set once (optional) remote build of given target has completed.
   */
  ListenableFuture<Void> waitForBuildRuleToFinishRemotely(BuildRule buildRule);

  /**
   * @return Future that will complete when most build rules have finished remotely. Value indicates
   *     whether most rules finished successfully or in a failure.
   */
  ListenableFuture<Boolean> waitForMostBuildRulesToFinishRemotely();
}
