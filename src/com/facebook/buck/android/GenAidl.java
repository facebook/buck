/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import static com.facebook.buck.jvm.java.Javac.SRC_ZIP;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JarDirectoryStep;
import com.facebook.buck.jvm.java.JarParameters;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

/**
 * Buildable for generating a .java file from an .aidl file. Example:
 *
 * <pre>
 * # This will generate IOrcaService.java in the buck-out/gen directory.
 * gen_aidl(
 *   name = 'orcaservice',
 *   aidl = 'IOrcaService.aidl',
 * )
 *
 * android_library(
 *   name = 'server',
 *   srcs = glob(['*.java']) + [':orcaservice'],
 *   deps = [
 *     '//first-party/orca/lib-base:lib-base',
 *   ],
 * )
 * </pre>
 */
public class GenAidl extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private final ToolchainProvider toolchainProvider;

  // TODO(#2493457): This rule uses the aidl binary (part of the Android SDK), so the RuleKey
  // should incorporate which version of aidl is used.
  @AddToRuleKey private final SourcePath aidlFilePath;
  @AddToRuleKey private final String importPath;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> aidlSrcs;
  private final Path output;
  private final Path genPath;

  GenAidl(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ToolchainProvider toolchainProvider,
      BuildRuleParams params,
      SourcePath aidlFilePath,
      String importPath,
      ImmutableSortedSet<SourcePath> aidlSrcs) {
    super(buildTarget, projectFilesystem, params);
    this.toolchainProvider = toolchainProvider;
    this.aidlFilePath = aidlFilePath;
    this.importPath = importPath;
    this.genPath = BuildTargets.getGenPath(getProjectFilesystem(), buildTarget, "%s");
    this.output =
        genPath.resolve(
            String.format("lib%s%s", buildTarget.getShortNameAndFlavorPostfix(), SRC_ZIP));
    this.aidlSrcs = aidlSrcs;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    commands.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), genPath)));

    BuildTarget target = getBuildTarget();
    Path outputDirectory = BuildTargets.getScratchPath(getProjectFilesystem(), target, "__%s.aidl");

    commands.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), outputDirectory)));

    AidlStep command =
        new AidlStep(
            getProjectFilesystem(),
            target,
            toolchainProvider,
            context.getSourcePathResolver().getAbsolutePath(aidlFilePath),
            ImmutableSet.of(importPath),
            outputDirectory);
    commands.add(command);

    // Files must ultimately be written to GEN_DIR to be used as source paths.
    Path genDirectory = getProjectFilesystem().getBuckPaths().getGenDir().resolve(importPath);

    // Warn the user if the genDirectory is not under the output directory.
    if (!importPath.startsWith(target.getBasePath().toString())) {
      // TODO(simons): Make this fatal. Give people some time to clean up their rules.
      context
          .getEventBus()
          .post(
              ConsoleEvent.warning(
                  "%s, gen_aidl import path (%s) should be a child of %s",
                  target, importPath, target.getBasePath()));
    }

    commands.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), genDirectory)));

    commands.add(
        new JarDirectoryStep(
            getProjectFilesystem(),
            JarParameters.builder()
                .setJarPath(output)
                .setEntriesToJar(ImmutableSortedSet.of(outputDirectory))
                .setMergeManifests(true)
                .build()));
    buildableContext.recordArtifact(output);

    return commands.build();
  }
}
