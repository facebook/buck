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

package com.facebook.buck.features.supermodule;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.util.zip.collect.OnDuplicateEntry;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.immutables.value.Value;

/** Description of {@code SupermoduleTargetGraph} build rule. */
public class SupermoduleTargetGraphDescription
    implements DescriptionWithTargetGraph<SupermoduleTargetGraphDescriptionArg>,
        VersionPropagator<SupermoduleTargetGraphDescriptionArg> {

  @Override
  public Class<SupermoduleTargetGraphDescriptionArg> getConstructorArgType() {
    return SupermoduleTargetGraphDescriptionArg.class;
  }

  private ImmutableSortedMap<BuildTarget, TargetInfo> getTargetGraphMap(
      BuildRuleCreationContextWithTargetGraph context,
      ImmutableSortedSet<BuildTarget> deps,
      Optional<Pattern> labelPattern) {
    ImmutableSortedMap.Builder<BuildTarget, TargetInfo> targetGraphMap =
        ImmutableSortedMap.naturalOrder();
    Iterable<TargetNode<?>> depTargetNodes = context.getTargetGraph().getAll(deps);
    TargetGraph subGraph = context.getTargetGraph().getSubgraph(depTargetNodes);
    ImmutableSet<TargetNode<?>> allNodes = subGraph.getNodes();

    Predicate<String> labelMatcher = labelPattern.map(p -> p.asPredicate()).orElse((l) -> true);

    for (TargetNode<?> node : allNodes) {
      ConstructorArg arg = node.getConstructorArg();
      ImmutableSet<String> labels =
          arg instanceof BuildRuleArg ? ((BuildRuleArg) arg).getLabels() : ImmutableSortedSet.of();
      ImmutableSet<BuildTarget> nodeDeps = node.getDeclaredDeps();
      targetGraphMap.put(
          node.getBuildTarget(),
          TargetInfo.of(
              nodeDeps.stream()
                  .map(t -> t.getFullyQualifiedName())
                  .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
              labels.stream()
                  .filter(l -> labelMatcher.test(l))
                  .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()))));
    }
    return targetGraphMap.build();
  }

  @Override
  public SupermoduleTargetGraph createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      SupermoduleTargetGraphDescriptionArg args) {
    return new SupermoduleTargetGraph(
        context.getActionGraphBuilder(),
        buildTarget,
        context.getProjectFilesystem(),
        new OutputPath(args.getOut()),
        getTargetGraphMap(context, args.getDeps(), args.getLabelPattern()));
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  /** Abstract class of args for the {@code SupermoduleTargetGraph} build rule. */
  @RuleArg
  interface AbstractSupermoduleTargetGraphDescriptionArg extends BuildRuleArg {
    @Value.Default
    default String getOut() {
      return getName() + ".json";
    }

    /** If provided, will only include labels that match the pattern in the output. */
    Optional<Pattern> getLabelPattern();

    @Hint(isTargetGraphOnlyDep = true)
    ImmutableSortedSet<BuildTarget> getDeps();

    @Value.Default
    default OnDuplicateEntry getOnDuplicateEntry() {
      return OnDuplicateEntry.OVERWRITE;
    }
  }
}
