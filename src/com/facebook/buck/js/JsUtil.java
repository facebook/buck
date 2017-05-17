/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.WorkerJobParams;
import com.facebook.buck.shell.WorkerProcessParams;
import com.facebook.buck.shell.WorkerProcessPoolFactory;
import com.facebook.buck.shell.WorkerShellStep;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class JsUtil {
  private JsUtil() {}

  static WorkerShellStep workerShellStep(
      WorkerTool worker,
      String jobArgs,
      BuildTarget buildTarget,
      SourcePathResolver sourcePathResolver,
      ProjectFilesystem projectFilesystem) {
    final Tool tool = worker.getTool();
    final WorkerJobParams params =
        WorkerJobParams.of(
            jobArgs,
            WorkerProcessParams.of(
                worker.getTempDir(),
                tool.getCommandPrefix(sourcePathResolver),
                worker.getArgs(sourcePathResolver),
                tool.getEnvironment(sourcePathResolver),
                worker.getMaxWorkers(),
                worker.isPersistent()
                    ? Optional.of(buildTarget.getCellPath().toString() + buildTarget.toString())
                    : Optional.empty(),
                Optional.of(worker.getInstanceKey())));
    return new WorkerShellStep(
        Optional.of(params),
        Optional.empty(),
        Optional.empty(),
        new WorkerProcessPoolFactory(projectFilesystem));
  }

  static String resolveMapJoin(
      Collection<SourcePath> items,
      SourcePathResolver sourcePathResolver,
      Function<Path, String> mapper) {
    return items
        .stream()
        .map(sourcePathResolver::getAbsolutePath)
        .map(mapper)
        .collect(Collectors.joining(" "));
  }

  static BuildTarget verifyIsJsLibraryTarget(
      BuildTarget target, BuildTarget parent, TargetGraph targetGraph) {
    Description<?> targetDescription = targetGraph.get(target).getDescription();
    if (targetDescription.getClass() != JsLibraryDescription.class) {
      throw new HumanReadableException(
          "%s target '%s' can only depend on js_library targets, but one of its dependencies, "
              + "'%s', is of type %s.",
          buildRuleTypeForTarget(parent, targetGraph),
          parent,
          target,
          buildRuleTypeForTarget(target, targetGraph));
    }

    return target;
  }

  private static String buildRuleTypeForTarget(BuildTarget target, TargetGraph targetGraph) {
    return Description.getBuildRuleType(targetGraph.get(target).getDescription()).getName();
  }

  static BuildRuleParams withWorkerDependencyOnly(
      BuildRuleParams params, BuildRuleResolver resolver, BuildTarget worker) {
    BuildRule workerRule = resolver.getRule(worker);
    return copyParamsWithDependencies(params, workerRule);
  }

  static BuildRuleParams copyParamsWithDependencies(BuildRuleParams params, BuildRule... rules) {
    return params.copyReplacingDeclaredAndExtraDeps(
        Suppliers.ofInstance(ImmutableSortedSet.of()),
        Suppliers.ofInstance(ImmutableSortedSet.copyOf(rules)));
  }

  static SourcePath relativeToOutputRoot(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem, String subpath) {
    return new ExplicitBuildTargetSourcePath(
        buildTarget,
        BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s").resolve(subpath));
  }
}
