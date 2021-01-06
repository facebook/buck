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

package com.facebook.buck.infer;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableCollection;

/**
 * Used by descriptions to properly handle {@link InferPlatform}. Particularly:
 *
 * <p>During parsing and configuration it provides information about parse-time deps, for instance
 * when infer binary or infer config are provided by some targets that otherwise not needed for the
 * build.
 *
 * <p>During action graph creation it can be resolved to {@link InferPlatform}.
 */
public interface UnresolvedInferPlatform {
  /** Resolves to the platform. */
  InferPlatform resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration);

  /** Returns {@link InferPlatform} parse-time deps. */
  Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration);

  /** Helper method to add parse-time deps to target graph for nullsafe flavored targets. */
  static void addParseTimeDepsToInferFlavored(
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder,
      BuildTarget buildTarget,
      UnresolvedInferPlatform platform) {
    if (buildTarget.getFlavors().contains(InferNullsafe.INFER_NULLSAFE)) {
      targetGraphOnlyDepsBuilder.addAll(
          platform.getParseTimeDeps(buildTarget.getTargetConfiguration()));
    }
  }
}
