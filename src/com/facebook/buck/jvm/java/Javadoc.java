/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.HasMavenCoordinates;
import com.facebook.buck.maven.aether.AetherUtil;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.facebook.buck.zip.ZipStep;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

public class Javadoc extends AbstractBuildRuleWithDeclaredAndExtraDeps implements MavenPublishable {

  public static final Flavor DOC_JAR = InternalFlavor.of("doc");

  @AddToRuleKey private final ImmutableSet<SourcePath> sources;
  @AddToRuleKey private final Optional<String> mavenCoords;
  @AddToRuleKey private final Optional<SourcePath> mavenPomTemplate;
  @AddToRuleKey private final Iterable<HasMavenCoordinates> mavenDeps;

  private final Path output;
  private final Path scratchDir;

  protected Javadoc(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      Optional<String> mavenCoords,
      Optional<SourcePath> mavenPomTemplate,
      Iterable<HasMavenCoordinates> mavenDeps,
      ImmutableSet<SourcePath> sources) {
    super(buildTarget, projectFilesystem, buildRuleParams);

    this.mavenCoords = mavenCoords.map(coord -> AetherUtil.addClassifier(coord, "javadoc"));
    this.mavenPomTemplate = mavenPomTemplate;
    this.mavenDeps = mavenDeps;
    this.sources = sources;

    this.output =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem(),
            getBuildTarget(),
            String.format("%%s/%s-javadoc.jar", getBuildTarget().getShortName()));
    this.scratchDir =
        BuildTargetPaths.getScratchPath(
            getProjectFilesystem(),
            getBuildTarget(),
            String.format("%%s/%s-javadoc.tmp", getBuildTarget().getShortName()));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));
    steps.add(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output)));

    // Fast path: nothing to do so just create an empty zip and return.
    if (sources.isEmpty()) {
      steps.add(
          new ZipStep(
              getProjectFilesystem(),
              output,
              ImmutableSet.of(),
              /* junk paths */ false,
              ZipCompressionLevel.NONE,
              output));
      return steps.build();
    }

    Path sourcesListFilePath = scratchDir.resolve("all-sources.txt");

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), scratchDir)));
    // Write an @-file with all the source files in
    steps.add(
        new WriteFileStep(
            getProjectFilesystem(),
            Joiner.on("\n")
                .join(
                    sources
                        .stream()
                        .map(context.getSourcePathResolver()::getAbsolutePath)
                        .map(Path::toString)
                        .iterator()),
            sourcesListFilePath,
            /* can execute */ false));

    Path atArgs = scratchDir.resolve("options");
    // Write an @-file with the classpath
    StringBuilder argsBuilder = new StringBuilder("-classpath ");
    Joiner.on(File.pathSeparator)
        .appendTo(
            argsBuilder,
            getBuildDeps()
                .stream()
                .filter(HasClasspathEntries.class::isInstance)
                .flatMap(rule -> ((HasClasspathEntries) rule).getTransitiveClasspaths().stream())
                .map(context.getSourcePathResolver()::getAbsolutePath)
                .map(Object::toString)
                .iterator());
    steps.add(
        new WriteFileStep(
            getProjectFilesystem(), argsBuilder.toString(), atArgs, /* can execute */ false));

    Path uncompressedOutputDir = scratchDir.resolve("docs");

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), uncompressedOutputDir)));
    steps.add(
        new ShellStep(getProjectFilesystem().resolve(scratchDir)) {
          @Override
          protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
            return ImmutableList.of(
                "javadoc",
                "-Xdoclint:none",
                "-notimestamp",
                "-d",
                uncompressedOutputDir.getFileName().toString(),
                "@" + getProjectFilesystem().resolve(atArgs),
                "@" + getProjectFilesystem().resolve(sourcesListFilePath));
          }

          @Override
          public String getShortName() {
            return "javadoc";
          }
        });
    steps.add(
        new ZipStep(
            getProjectFilesystem(),
            output,
            ImmutableSet.of(),
            /* junk paths */ false,
            ZipCompressionLevel.DEFAULT,
            uncompressedOutputDir));

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public Iterable<HasMavenCoordinates> getMavenDeps() {
    return mavenDeps;
  }

  @Override
  public Iterable<BuildRule> getPackagedDependencies() {
    return ImmutableSet.of(this); // I think that this is right
  }

  @Override
  public Optional<SourcePath> getPomTemplate() {
    return mavenPomTemplate;
  }
}
