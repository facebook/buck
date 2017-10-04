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

package com.facebook.buck.gwt;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.CopyResourcesStep;
import com.facebook.buck.jvm.java.JarDirectoryStep;
import com.facebook.buck.jvm.java.JarParameters;
import com.facebook.buck.jvm.java.ResourcesParameters;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link com.facebook.buck.rules.BuildRule} whose output file is a JAR containing the .java files
 * and resources suitable for a GWT module. (It differs slightly from a source JAR because it
 * contains resources.)
 */
public class GwtModule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private final Path outputFile;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> filesForGwtModule;
  private final SourcePathRuleFinder ruleFinder;

  GwtModule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      ImmutableSortedSet<SourcePath> filesForGwtModule) {
    super(buildTarget, projectFilesystem, params);
    this.ruleFinder = ruleFinder;
    this.outputFile =
        BuildTargets.getGenPath(
            getProjectFilesystem(),
            buildTarget,
            "__gwt_module_%s__/" + buildTarget.getShortNameAndFlavorPostfix() + ".jar");
    this.filesForGwtModule = filesForGwtModule;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path workingDirectory = outputFile.getParent();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), workingDirectory)));

    // A CopyResourcesStep is needed so that a file that is at java/com/example/resource.txt in the
    // repository will be added as com/example/resource.txt in the resulting JAR (assuming that
    // "/java/" is listed under src_roots in .buckconfig).
    Path tempJarFolder = workingDirectory.resolve("tmp");
    steps.add(
        new CopyResourcesStep(
            getProjectFilesystem(),
            context,
            ruleFinder,
            getBuildTarget(),
            ResourcesParameters.builder()
                .setResources(filesForGwtModule)
                .setResourcesRoot(Optional.empty())
                .build(),
            tempJarFolder));

    steps.add(
        new JarDirectoryStep(
            getProjectFilesystem(),
            JarParameters.builder()
                .setJarPath(outputFile)
                .setEntriesToJar(ImmutableSortedSet.of(tempJarFolder))
                .setMergeManifests(true)
                .build()));

    buildableContext.recordArtifact(outputFile);

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputFile);
  }
}
