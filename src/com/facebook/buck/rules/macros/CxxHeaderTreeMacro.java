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

import com.facebook.buck.core.model.HostTargetConfigurationResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** <code>$(header-tree)</code> macro type. */
@BuckStyleValue
public interface CxxHeaderTreeMacro extends Macro, UnconfiguredMacro {

  @Override
  default Class<? extends UnconfiguredMacro> getUnconfiguredMacroClass() {
    return CxxHeaderTreeMacro.class;
  }

  @Override
  default Class<? extends Macro> getMacroClass() {
    return CxxHeaderTreeMacro.class;
  }

  static CxxHeaderTreeMacro of() {
    return ImmutableCxxHeaderTreeMacro.of();
  }

  @Override
  default Macro configure(
      TargetConfiguration targetConfiguration,
      HostTargetConfigurationResolver hostConfigurationResolver) {
    return this;
  }
}
