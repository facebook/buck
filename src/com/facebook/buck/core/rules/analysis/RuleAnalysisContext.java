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

package com.facebook.buck.core.rules.analysis;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.analysis.context.DependencyOnlyRuleAnalysisContext;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;

/**
 * A context that will be supplied to a {@link com.facebook.buck.core.description.Description}'s
 * rule implementation so that the rule analysis can be performed. This context will be used to
 * create the rule's {@link com.google.devtools.build.lib.packages.Provider}, and register
 * corresponding Actions. Actions will be {@link com.facebook.buck.core.rules.BuildRule}s for now,
 * but we will eventually migrate to some other representation of the set of {@link
 * com.facebook.buck.step.Step}.
 *
 * <p>The {@link RuleAnalysisContext} will be very similar to Bazel's {@code
 * com.google.devtools.build.lib.analysis.RuleContext}. {@see <a
 * href="https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/analysis/RuleContext.java">RuleContext</a>}
 *
 * <p>This will contain information on the configurations of the current rule, information about
 * it's transitive dependencies via {@link com.google.devtools.build.lib.packages.Provider}s and
 * their corresponding {@link com.google.devtools.build.lib.packages.InfoInterface}s. It will also
 * offer ways to register {@link com.facebook.buck.step.Step}s.
 */
public interface RuleAnalysisContext extends DependencyOnlyRuleAnalysisContext {

  /**
   * @return a map of the {@link ProviderInfoCollection} of the providers propagated by each of the
   *     dependencies requested.
   */
  @Override
  Map<BuildTarget, ProviderInfoCollection> resolveDeps(Iterable<BuildTarget> deps);

  /** same as {@link #resolveDeps(Iterable)} but for one dep */
  @Override
  ProviderInfoCollection resolveDep(BuildTarget dep);

  /**
   * @return a list of the {@link Artifact} of the sources of the providers propagated by each of
   *     the source paths requested.
   */
  @Override
  ImmutableSortedSet<Artifact> resolveSrcs(Iterable<SourcePath> srcs);

  /** same as {@link #resolveSrcs(Iterable)} but for one src */
  @Override
  Artifact resolveSrc(SourcePath src);

  /**
   * @return the factory for creating {@link com.facebook.buck.core.artifact.Artifact}s and
   *     registering {@link com.facebook.buck.core.rules.actions.Action}s
   */
  ActionRegistry actionRegistry();

  /** @return an {@link BuckEventBus} for sending stats and printing */
  BuckEventBus getEventBus();

  // TODO(bobyf): Fill as needed. This will probably contain most of {@link
  // BuildRuleCreationContext}
}
