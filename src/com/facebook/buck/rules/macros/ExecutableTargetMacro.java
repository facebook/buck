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

import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Like {@code exe} macro but does not switch to host platform */
@BuckStyleValue
public abstract class ExecutableTargetMacro extends AbstractExecutableTargetOrHostMacro {

  @Override
  public Class<? extends Macro> getMacroClass() {
    return ExecutableTargetMacro.class;
  }

  @Override
  protected ExecutableTargetMacro withTargetWithOutputs(BuildTargetWithOutputs target) {
    return of(target);
  }

  public static ExecutableTargetMacro of(BuildTargetWithOutputs buildTarget) {
    return ImmutableExecutableTargetMacro.of(buildTarget);
  }
}
