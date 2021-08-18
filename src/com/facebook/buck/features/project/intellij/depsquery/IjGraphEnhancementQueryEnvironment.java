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

package com.facebook.buck.features.project.intellij.depsquery;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.query.ConfiguredQueryTarget;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.query.GraphEnhancementQueryEnvironment;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A {@link GraphEnhancementQueryEnvironment} that doesn't require an action graph, and all methods
 * that require {@link com.facebook.buck.core.rules.ActionGraphBuilder} performs no-op. This class
 * should only be used for IJ project generation.
 */
public class IjGraphEnhancementQueryEnvironment extends GraphEnhancementQueryEnvironment {

  public IjGraphEnhancementQueryEnvironment(
      TargetGraph targetGraph,
      TypeCoercerFactory typeCoercerFactory,
      CellNameResolver cellNameResolver,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      BaseName targetBaseName,
      Set<BuildTarget> declaredDeps,
      TargetConfiguration targetConfiguration) {
    super(
        Optional.empty(),
        Optional.of(targetGraph),
        typeCoercerFactory,
        cellNameResolver,
        unconfiguredBuildTargetFactory,
        targetBaseName,
        declaredDeps,
        targetConfiguration);
  }

  /**
   * Overrides the method to avoid accessing {@link com.facebook.buck.core.rules.ActionGraphBuilder}
   */
  @Override
  public Stream<ConfiguredQueryTarget> restrictToInstancesOf(
      Set<ConfiguredQueryTarget> targets, Class<?> clazz) {
    return Stream.empty();
  }

  /**
   * Overrides the method to avoid accessing {@link com.facebook.buck.core.rules.ActionGraphBuilder}
   */
  @Override
  public Stream<ConfiguredQueryTarget> getFirstOrderClasspath(Set<ConfiguredQueryTarget> targets) {
    return Stream.empty();
  }
}
