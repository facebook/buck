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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Type for the `$(worker ...)` macro. */
@BuckStyleValue
public abstract class UnconfiguredWorkerMacro extends UnconfiguredBuildTargetMacro {

  @Override
  public WorkerMacro configure(
      TargetConfiguration targetConfiguration, TargetConfiguration hostConfiguration) {
    return WorkerMacro.of(getTargetWithOutputs().configure(targetConfiguration));
  }

  public static UnconfiguredWorkerMacro of(UnconfiguredBuildTargetWithOutputs buildTarget) {
    return ImmutableUnconfiguredWorkerMacro.ofImpl(buildTarget);
  }
}
