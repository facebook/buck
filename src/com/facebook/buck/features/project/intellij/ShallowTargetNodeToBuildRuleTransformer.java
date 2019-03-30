/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidInstrumentationApkDescription;
import com.facebook.buck.android.AndroidInstrumentationTestDescription;
import com.facebook.buck.android.AndroidManifestDescription;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.ImmutableBuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.shell.GenruleDescriptionArg;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Generates build rules as if they have no dependencies, so that action graph generation visits
 * each target node only once. This produces a sufficiently complete action graph to generate .iml
 * files.
 */
public class ShallowTargetNodeToBuildRuleTransformer implements TargetNodeToBuildRuleTransformer {

  // These build rules are expensive to build and building them does not add any extra information
  // (source folders etc.) to the action graph that is needed by module rules.
  private static final ImmutableSet<Class<? extends DescriptionWithTargetGraph<?>>>
      UNUSED_BUILD_RULE_DESCRIPTION_CLASSES =
          ImmutableSet.of(
              AndroidBinaryDescription.class,
              AndroidInstrumentationApkDescription.class,
              AndroidInstrumentationTestDescription.class,
              AndroidManifestDescription.class);

  public ShallowTargetNodeToBuildRuleTransformer() {}

  @Override
  public <T> BuildRule transform(
      ToolchainProvider toolchainProvider,
      TargetGraph targetGraph,
      ActionGraphBuilder graphBuilder,
      TargetNode<T> targetNode) {
    DescriptionWithTargetGraph<T> description =
        (DescriptionWithTargetGraph<T>) targetNode.getDescription();

    T arg = targetNode.getConstructorArg();

    if (UNUSED_BUILD_RULE_DESCRIPTION_CLASSES.contains(description.getClass())
        || isNonExecutableGenrule(arg, description.getClass())) {
      String outputPath;
      if (description.getClass().equals(GenruleDescription.class)) {
        outputPath = ((GenruleDescriptionArg) arg).getOut();
      } else {
        // This path is never used, but it needs to be unique, to prevent conflicts creating
        // resource maps while creating java build rules.
        outputPath =
            Util.normalizeIntelliJName(targetNode.getBuildTarget().getFullyQualifiedName());
      }
      return new EmptyBuildRule(
          targetNode.getBuildTarget(), targetNode.getFilesystem(), outputPath);
    } else {
      BuildRuleParams params =
          new BuildRuleParams(
              ImmutableSortedSet::of, ImmutableSortedSet::of, ImmutableSortedSet.of());

      BuildRuleCreationContextWithTargetGraph context =
          ImmutableBuildRuleCreationContextWithTargetGraph.of(
              targetGraph,
              graphBuilder,
              targetNode.getFilesystem(),
              targetNode.getCellNames(),
              toolchainProvider);

      return description.createBuildRule(context, targetNode.getBuildTarget(), params, arg);
    }
  }

  private <T> boolean isNonExecutableGenrule(T arg, Class<?> descriptionClass) {
    if (!AbstractGenruleDescription.class.isAssignableFrom(descriptionClass)) {
      return false;
    }
    // This is a genrule, is it executable?
    return !(arg instanceof GenruleDescriptionArg)
        || !((GenruleDescriptionArg) arg).getExecutable().orElse(false);
  }
}
