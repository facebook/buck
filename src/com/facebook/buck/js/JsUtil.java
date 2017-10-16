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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.WorkerShellStep;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.worker.WorkerJobParams;
import com.facebook.buck.worker.WorkerProcessIdentity;
import com.facebook.buck.worker.WorkerProcessParams;
import com.facebook.buck.worker.WorkerProcessPoolFactory;
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
                tool.getEnvironment(sourcePathResolver),
                worker.getMaxWorkers(),
                worker.isPersistent()
                    ? Optional.of(
                        WorkerProcessIdentity.of(
                            buildTarget.getCellPath().toString() + buildTarget.toString(),
                            worker.getInstanceKey()))
                    : Optional.empty()));
    return new WorkerShellStep(
        buildTarget,
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

  static boolean isJsLibraryTarget(BuildTarget target, TargetGraph targetGraph) {
    return targetGraph.get(target).getDescription() instanceof JsLibraryDescription;
  }

  static BuildRuleParams withWorkerDependencyOnly(
      BuildRuleParams params, BuildRuleResolver resolver, BuildTarget worker) {
    BuildRule workerRule = resolver.getRule(worker);
    return copyParamsWithDependencies(params, workerRule);
  }

  static BuildRuleParams copyParamsWithDependencies(BuildRuleParams params, BuildRule... rules) {
    return params.withoutDeclaredDeps().withExtraDeps(ImmutableSortedSet.copyOf(rules));
  }

  static SourcePath relativeToOutputRoot(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem, String subpath) {
    return ExplicitBuildTargetSourcePath.of(
        buildTarget,
        BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s").resolve(subpath));
  }

  public static String getSourcemapPath(JsBundleOutputs jsBundleOutputs) {
    return String.format("map/%s.map", jsBundleOutputs.getBundleName());
  }
}
