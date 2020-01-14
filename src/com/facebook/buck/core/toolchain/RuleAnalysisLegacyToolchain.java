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

package com.facebook.buck.core.toolchain;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.analysis.context.DependencyOnlyRuleAnalysisContext;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import java.util.function.Consumer;

/**
 * Allows legacy {@link Toolchain}s to be exposed in a generic way to the rule analysis graph via
 * providers.
 */
public interface RuleAnalysisLegacyToolchain {
  /**
   * @return the providers that describe this specific toolchain. e.g. A C++ toolchain might return
   *     a C++ToolchainInfo object that has a compiler, and default compiler flags
   */
  ProviderInfoCollection getProviders(
      DependencyOnlyRuleAnalysisContext context, TargetConfiguration targetConfiguration);

  /**
   * Add the dependencies for this toolchain to the graph. This includes things like build targets
   * for a compiler or linker, etc.
   *
   * @param targetConfiguration the configuration this toolchain will be used in
   * @param builder the builder to add dependencies to
   */
  void visitToolDependencies(
      TargetConfiguration targetConfiguration, Consumer<BuildTarget> builder);
}
