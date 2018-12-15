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

package com.facebook.buck.features.gwt;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.CopyResourcesStep;
import com.facebook.buck.jvm.java.JarDirectoryStep;
import com.facebook.buck.jvm.java.JarParameters;
import com.facebook.buck.jvm.java.ResourcesParameters;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link BuildRule} whose output file is a JAR containing the .java files and resources suitable
 * for a GWT module. (It differs slightly from a source JAR because it contains resources.)
 */
public class GwtModule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private final Path outputFile;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> filesForGwtModule;
  private final SourcePathRuleFinder ruleFinder;
  @AddToRuleKey private final Optional<String> resourcesRoot;

  GwtModule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      ImmutableSortedSet<SourcePath> filesForGwtModule,
      Optional<String> resourcesRoot) {
    super(buildTarget, projectFilesystem, params);
    this.ruleFinder = ruleFinder;
    this.outputFile =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem(),
            buildTarget,
            "__gwt_module_%s__/" + buildTarget.getShortNameAndFlavorPostfix() + ".jar");
    this.filesForGwtModule = filesForGwtModule;
    this.resourcesRoot = resourcesRoot;
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
            getBuildTarget(),
            ResourcesParameters.builder()
                .setResources(
                    ResourcesParameters.getNamedResources(
                        context.getSourcePathResolver(),
                        ruleFinder,
                        getProjectFilesystem(),
                        filesForGwtModule))
                .setResourcesRoot(resourcesRoot)
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
