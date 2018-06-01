/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.go;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.SortedSet;

public class CGoGenImport extends AbstractBuildRule {
  @AddToRuleKey private final Tool cgo;
  @AddToRuleKey private final GoPlatform platform;
  @AddToRuleKey private final SourcePath cgoBin;

  private final ImmutableSortedSet<BuildRule> deps;
  private final SourcePathResolver pathResolver;
  private final Path packageName;
  private final Path outputFile;
  private final Path genDir;

  public CGoGenImport(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      Tool cgo,
      GoPlatform platform,
      Path packageName,
      SourcePath cgoBin) {
    super(buildTarget, projectFilesystem);

    this.pathResolver = pathResolver;
    this.cgo = cgo;
    this.platform = platform;
    this.packageName = packageName;
    this.genDir = BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s/gen/");
    this.outputFile = genDir.resolve("_cgo_import.go");
    this.cgoBin = cgoBin;
    this.deps =
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(BuildableSupport.getDepsCollection(cgo, ruleFinder))
            .addAll(ruleFinder.filterBuildRuleInputs(cgoBin))
            .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), genDir)));
    steps.add(
        new CGoGenerateImportStep(
            getBuildTarget(),
            getProjectFilesystem().getRootPath(),
            cgo.getCommandPrefix(pathResolver),
            platform,
            packageName,
            pathResolver.getAbsolutePath(cgoBin),
            outputFile));

    buildableContext.recordArtifact(outputFile);
    return steps.build();
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return deps;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputFile);
  }
}
