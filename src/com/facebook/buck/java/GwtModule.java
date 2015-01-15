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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * {@link com.facebook.buck.rules.BuildRule} whose output file is a JAR containing the .java files
 * and resources suitable for a GWT module. (It differs slightly from a source JAR because it
 * contains resources.)
 */
public class GwtModule extends AbstractBuildRule {

  // TODO(mbolin): Figure out how to move this to the com.facebook.buck.gwt package.
  // To do that, we need a way to register a flavor with a Description from outside the package.

  private final Path outputFile;
  private final ImmutableSortedSet<SourcePath> filesForGwtModule;

  GwtModule(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableSortedSet<SourcePath> filesForGwtModule) {
    super(params, resolver);
    BuildTarget target = params.getBuildTarget();
    this.outputFile = BuildTargets.getGenPath(
        target,
        "__gwt_module_%s__/" + target.getShortNameAndFlavorPostfix() + ".jar");
    this.filesForGwtModule = filesForGwtModule;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(filesForGwtModule);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path workingDirectory = outputFile.getParent();
    steps.add(new MakeCleanDirectoryStep(workingDirectory));

    // A CopyResourcesStep is needed so that a file that is at java/com/example/resource.txt in the
    // repository will be added as com/example/resource.txt in the resulting JAR (assuming that
    // "/java/" is listed under src_roots in .buckconfig).
    Path tempJarFolder = workingDirectory.resolve("tmp");
    steps.add(new CopyResourcesStep(
        getResolver(),
        getBuildTarget(),
        filesForGwtModule,
        tempJarFolder,
        context.getJavaPackageFinder()));

    steps.add(new JarDirectoryStep(
        outputFile,
        /* entriesToJar */ ImmutableSet.of(tempJarFolder),
        /* mainClass */ null,
        /* manifestFile */ null));

    buildableContext.recordArtifact(outputFile);

    return steps.build();
  }

  @Override
  public Builder appendDetailsToRuleKey(Builder builder) {
    return builder.setReflectively("filesForGwtModule", filesForGwtModule);
  }

  @Override
  public Path getPathToOutputFile() {
    return outputFile;
  }
}
