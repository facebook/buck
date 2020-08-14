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
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Unconfigured graph version of {@link CxxGenruleFilterAndTargetsMacro}. */
public abstract class UnconfiguredCxxGenruleFilterAndTargetsMacro implements UnconfiguredMacro {

  public abstract Optional<Pattern> getFilter();

  public abstract ImmutableList<UnconfiguredBuildTargetWithOutputs> getTargetsWithOutputs();

  abstract BiFunction<
          Optional<Pattern>, ImmutableList<BuildTargetWithOutputs>, CxxGenruleFilterAndTargetsMacro>
      getConfiguredFactory();

  /** Apply the configuration. */
  @Override
  public CxxGenruleFilterAndTargetsMacro configure(
      TargetConfiguration targetConfiguration, TargetConfiguration hostConfiguration) {
    ImmutableList<BuildTargetWithOutputs> targetsWithOutputs =
        getTargetsWithOutputs().stream()
            .map(t -> t.configure(targetConfiguration))
            .collect(ImmutableList.toImmutableList());
    return getConfiguredFactory().apply(getFilter(), targetsWithOutputs);
  }
}
