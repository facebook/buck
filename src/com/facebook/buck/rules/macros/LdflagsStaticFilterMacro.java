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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.regex.Pattern;

/** <code>$(ldflags-static-filter ...)</code> macro type. */
@BuckStyleValue
public abstract class LdflagsStaticFilterMacro extends CxxGenruleFilterAndTargetsMacro {

  @Override
  public Class<? extends Macro> getMacroClass() {
    return LdflagsStaticFilterMacro.class;
  }

  @Override
  LdflagsStaticFilterMacro withTargets(ImmutableList<BuildTarget> targets) {
    return ImmutableLdflagsStaticFilterMacro.of(getFilter(), targets);
  }

  public static LdflagsStaticFilterMacro of(
      Optional<Pattern> pattern, ImmutableList<BuildTarget> buildTargets) {
    return ImmutableLdflagsStaticFilterMacro.of(pattern, buildTargets);
  }
}
