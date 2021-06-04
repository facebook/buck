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

package com.facebook.buck.workertool.impl.request;

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.downward.model.ResultEvent;
import com.google.common.util.concurrent.SettableFuture;

/**
 * Holds execution action details. Represents a single currently execution action of a larger
 * external worker tool request. {@link ExecutionRequest}
 */
@BuckStyleValue
public abstract class ExecutingAction {

  public abstract ActionId getActionId();

  public abstract SettableFuture<ResultEvent> getResultEventFuture();

  public static ExecutingAction of(ActionId actionId) {
    return ImmutableExecutingAction.ofImpl(actionId, SettableFuture.create());
  }
}
