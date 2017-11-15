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
package com.facebook.buck.rules;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * Used by a local build to wait for remote completion of build rules (if there is a remote build)
 */
public interface RemoteBuildRuleCompletionWaiter {

  /**
   * When performing a remote/distributed build, Future will get set once the given build target has
   * finished building remotely. For a non remote build, this operation is a no-op and the Future
   * will return immediately.
   *
   * @return Future that gets set once (optional) remote build of given target has completed.
   */
  ListenableFuture<Void> waitForBuildRuleToFinishRemotely(BuildRule buildRule);
}
