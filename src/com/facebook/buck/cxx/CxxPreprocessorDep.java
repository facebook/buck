/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.google.common.collect.ImmutableMap;

/**
 * An interface that represents a {@link BuildRule} which can contribute components (e.g. header
 * files, preprocessor macros) to the preprocessing of some top-level file (e.g. a C++ source from a
 * C++ library rule).
 */
public interface CxxPreprocessorDep {

  BuildTarget getBuildTarget();

  Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver);

  /**
   * Returns the preprocessor input that represents this rule's public (exported) declarations. This
   * includes any exported preprocessor flags, headers, etc.
   */
  CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  /**
   * Returns all transitive preprocessor inputs for this library. This includes public headers (and
   * exported preprocessor flags) of all exported dependencies.
   */
  ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);
}
