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

package com.facebook.buck.core.rules.analysis.context;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;

/**
 * Reduced context from {@link com.facebook.buck.core.rules.analysis.RuleAnalysisContext} that only
 * resolves deps and sources. This primarily exists to get around a circular dependency in some
 * places that we want to use providers (namely in toolchains, where {@link
 * com.facebook.buck.core.rules.actions.ActionRegistry} causes a circular dependency)
 */
public interface DependencyOnlyRuleAnalysisContext {
  /**
   * @return a map of the {@link ProviderInfoCollection} of the providers propagated by each of the
   *     dependencies requested.
   */
  Map<BuildTarget, ProviderInfoCollection> resolveDeps(Iterable<BuildTarget> deps);

  /** same as {@link #resolveDeps(Iterable)} but for one dep */
  ProviderInfoCollection resolveDep(BuildTarget dep);

  /**
   * @return a list of the {@link Artifact} of the sources of the providers propagated by each of
   *     the source paths requested.
   */
  ImmutableSortedSet<Artifact> resolveSrcs(Iterable<SourcePath> srcs);

  /** same as {@link #resolveSrcs(Iterable)} but for one src */
  Artifact resolveSrc(SourcePath src);
}
