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

package com.facebook.buck.core.build.execution.context.actionid;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

/** Executing action id. Typically represents that fully qualified name of the build target. */
@BuckStyleValue
public abstract class ActionId {

  public abstract String getValue();

  public static ActionId of(BuildTarget buildTarget) {
    return of(buildTarget.toStringWithConfiguration());
  }

  public static ActionId of(String value) {
    Preconditions.checkState(!Strings.isNullOrEmpty(value), "action id has to be not empty");
    return ImmutableActionId.ofImpl(value);
  }
}
