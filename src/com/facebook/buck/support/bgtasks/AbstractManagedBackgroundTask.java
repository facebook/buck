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

package com.facebook.buck.support.bgtasks;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/**
 * Wrapper class for {@link BackgroundTask} that includes data generated/ managed by the {@link
 * BackgroundTaskManager}, e.g. task ID.
 */
@Value.Immutable(builder = false)
@BuckStyleImmutable
abstract class AbstractManagedBackgroundTask {

  @Value.Parameter
  public abstract BackgroundTask<?> getTask();

  @Value.Parameter
  public abstract TaskId getId();

  /** Task ID object for {@link }. */
  @Value.Immutable(builder = false)
  @BuckStyleImmutable
  abstract static class AbstractTaskId {
    @Value.Parameter
    abstract String name();

    @Value.Parameter
    abstract BuildId buildId();
  }
}
