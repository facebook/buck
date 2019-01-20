/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.core.rules.analysis;

import com.facebook.buck.core.rules.actions.ActionAnalysisDataRegistry;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.google.common.collect.ImmutableMap;

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
public interface RuleAnalysisContext extends ActionAnalysisDataRegistry {

  /**
   * @return a {@link ProviderInfoCollection} of the providers propagated for each dependency target
   *     as a map with key of the {@link RuleAnalysisKey} that correspond to the {@link
   *     ProviderInfoCollection}.
   */
  ImmutableMap<RuleAnalysisKey, ProviderInfoCollection> deps();

  // TODO(bobyf): Fill as needed. This will probably contain most of {@link
  // BuildRuleCreationContext}
}
