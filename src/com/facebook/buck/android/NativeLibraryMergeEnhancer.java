/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Helper for AndroidLibraryGraphEnhancer to handle semi-transparent merging of native libraries.
 *
 * Older versions of Android have a limit on how many DSOs they can load into one process.
 * To work around this limit, it can be helpful to merge multiple libraries together
 * based on a per-app configuration.  This enhancer replaces the raw NativeLinkable rules
 * with versions that merge multiple logical libraries into one physical library.
 * We also generate code to allow the merge results to be queried at runtime.
 */
class NativeLibraryMergeEnhancer {
  private NativeLibraryMergeEnhancer() {}

  static NativeLibraryMergeEnhancementResult enhance(
      CxxBuckConfig cxxBuckConfig,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      BuildRuleParams buildRuleParams,
      Map<String, List<Pattern>> mergeMap,
      ImmutableList<NativeLinkable> linkables,
      ImmutableList<NativeLinkable> linkablesAssets) {
    // Suppress warnings.
    cxxBuckConfig.getClass();
    ruleResolver.getClass();
    pathResolver.getClass();
    mergeMap.getClass();
    buildRuleParams.getClass();

    return NativeLibraryMergeEnhancementResult.builder()
        .addAllMergedLinkables(linkables)
        .addAllMergedLinkablesAssets(linkablesAssets)
        .build();
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractNativeLibraryMergeEnhancementResult {
    public abstract ImmutableList<NativeLinkable> getMergedLinkables();
    public abstract ImmutableList<NativeLinkable> getMergedLinkablesAssets();
  }
}
