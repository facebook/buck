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
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.regex.Pattern;

/** Unconfigured graph version of {@link CxxGenruleFilterAndTargetsMacro}. */
@BuckStyleValue
public abstract class UnconfiguredCxxGenruleFilterAndTargetsMacro implements UnconfiguredMacro {

  /** Which macro is this. */
  public enum Which {
    CPPFLAGS,
    CXXPPFLAGS,
    CUDAPPFLAGS,
    LDFLAGS_SHARED,
    LDFLAGS_SHARED_FILTER,
    LDFLAGS_STATIC,
    LDFLAGS_STATIC_FILTER,
    LDFLAGS_STATIC_PIC,
    LDFLAGS_STATIC_PIC_FILTER,
  }

  public abstract Which getWhich();

  public abstract Optional<Pattern> getFilter();

  public abstract ImmutableList<UnconfiguredBuildTarget> getTargets();

  public static UnconfiguredCxxGenruleFilterAndTargetsMacro of(
      Which which, Optional<Pattern> filter, ImmutableList<UnconfiguredBuildTarget> targets) {
    return ImmutableUnconfiguredCxxGenruleFilterAndTargetsMacro.ofImpl(which, filter, targets);
  }

  /** Apply the configuration. */
  @Override
  public CxxGenruleFilterAndTargetsMacro configure(
      TargetConfiguration targetConfiguration, TargetConfiguration hostConfiguration) {
    ImmutableList<BuildTarget> configuredTargets =
        getTargets().stream()
            .map(t -> t.configure(targetConfiguration))
            .collect(ImmutableList.toImmutableList());
    switch (getWhich()) {
      case CPPFLAGS:
        return CppFlagsMacro.of(getFilter(), configuredTargets);
      case CXXPPFLAGS:
        return CxxppFlagsMacro.of(getFilter(), configuredTargets);
      case CUDAPPFLAGS:
        return CudappFlagsMacro.of(getFilter(), configuredTargets);
      case LDFLAGS_SHARED:
        return LdflagsSharedMacro.of(getFilter(), configuredTargets);
      case LDFLAGS_SHARED_FILTER:
        return LdflagsSharedFilterMacro.of(getFilter(), configuredTargets);
      case LDFLAGS_STATIC:
        return LdflagsStaticMacro.of(getFilter(), configuredTargets);
      case LDFLAGS_STATIC_FILTER:
        return LdflagsStaticFilterMacro.of(getFilter(), configuredTargets);
      case LDFLAGS_STATIC_PIC:
        return LdflagsStaticPicMacro.of(getFilter(), configuredTargets);
      case LDFLAGS_STATIC_PIC_FILTER:
        return LdflagsStaticPicFilterMacro.of(getFilter(), configuredTargets);
      default:
        throw new AssertionError();
    }
  }
}
