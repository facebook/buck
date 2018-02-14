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
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Buildable that produces a GWT application as a WAR file, which is a zip of the outputs produced
 * by the GWT compiler.
 */
public class GwtBinary extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  /**
   * Valid values for the GWT Compiler's {@code -style} flag. Acceptable values are defined in the
   * GWT docs at http://bit.ly/1sclx5O.
   */
  enum Style {
    /** Named "obf" for "obfuscated". This is the default style. */
    OBF,
    PRETTY,
    DETAILED,
    ;
  }

  private static final String GWT_COMPILER_CLASS = "com.google.gwt.dev.Compiler";

  private final Path outputFile;
  @AddToRuleKey private final ImmutableSortedSet<String> modules;
  @AddToRuleKey private final Tool javaRuntimeLauncher;
  @AddToRuleKey private final ImmutableList<String> vmArgs;
  @AddToRuleKey private final Style style;
  @AddToRuleKey private final boolean draftCompile;
  @AddToRuleKey private final int optimize;
  @AddToRuleKey private final int localWorkers;
  @AddToRuleKey private final boolean strict;
  @AddToRuleKey private final ImmutableList<String> experimentalArgs;
  // Deliberately not added to rule key
  private final ImmutableSortedSet<SourcePath> gwtModuleJars;

  /** @param modules The GWT modules to build with the GWT compiler. */
  GwtBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      ImmutableSortedSet<String> modules,
      Tool javaRuntimeLauncher,
      List<String> vmArgs,
      Style style,
      boolean draftCompile,
      int optimize,
      int localWorkers,
      boolean strict,
      List<String> experimentalArgs,
      ImmutableSortedSet<SourcePath> gwtModuleJars) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.outputFile =
        BuildTargets.getGenPath(
            projectFilesystem,
            buildTarget,
            "__gwt_binary_%s__/" + buildTarget.getShortNameAndFlavorPostfix() + ".zip");
    this.modules = modules;
    Preconditions.checkArgument(
        !modules.isEmpty(), "Must specify at least one module for %s.", buildTarget);
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.vmArgs = ImmutableList.copyOf(vmArgs);
    this.style = style;
    this.draftCompile = draftCompile;

    // No need for bounds-checking this int because GWT does it internally: http://bit.ly/1kFN5M7.
    this.optimize = optimize;

    Preconditions.checkArgument(
        localWorkers > 0, "localWorkers must be greater than zero: %d", localWorkers);
    this.localWorkers = localWorkers;

    this.strict = strict;
    this.experimentalArgs = ImmutableList.copyOf(experimentalArgs);
    this.gwtModuleJars = gwtModuleJars;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Create a clean directory where the .zip file will be written.
    Path workingDirectory =
        context.getSourcePathResolver().getRelativePath(getSourcePathToOutput()).getParent();
    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), workingDirectory)));

    // Write the deploy files into a separate directory so that the generated .zip is smaller.
    final Path deployDirectory = workingDirectory.resolve("deploy");
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), deployDirectory)));

    Step javaStep =
        new ShellStep(Optional.of(getBuildTarget()), projectFilesystem.getRootPath()) {
          @Override
          public String getShortName() {
            return "gwt-compile";
          }

          @Override
          protected ImmutableList<String> getShellCommandInternal(
              ExecutionContext executionContext) {
            ImmutableList.Builder<String> javaArgsBuilder = ImmutableList.builder();
            javaArgsBuilder.addAll(
                javaRuntimeLauncher.getCommandPrefix(context.getSourcePathResolver()));
            javaArgsBuilder.add("-Dgwt.normalizeTimestamps=true");
            javaArgsBuilder.addAll(vmArgs);
            javaArgsBuilder.add(
                "-classpath",
                Joiner.on(File.pathSeparator)
                    .join(
                        Iterables.transform(
                            getClasspathEntries(context.getSourcePathResolver()),
                            getProjectFilesystem()::resolve)),
                GWT_COMPILER_CLASS,
                "-war",
                context.getSourcePathResolver().getAbsolutePath(getSourcePathToOutput()).toString(),
                "-style",
                style.name(),
                "-optimize",
                String.valueOf(optimize),
                "-localWorkers",
                String.valueOf(localWorkers),
                "-deploy",
                getProjectFilesystem().resolve(deployDirectory).toString());
            if (draftCompile) {
              javaArgsBuilder.add("-draftCompile");
            }
            if (strict) {
              javaArgsBuilder.add("-strict");
            }
            javaArgsBuilder.addAll(experimentalArgs);
            javaArgsBuilder.addAll(modules);
            final ImmutableList<String> javaArgs = javaArgsBuilder.build();

            return javaArgs;
          }
        };
    steps.add(javaStep);

    buildableContext.recordArtifact(
        context.getSourcePathResolver().getRelativePath(getSourcePathToOutput()));

    return steps.build();
  }

  /** @return The {@code .zip} file produced by this rule. */
  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputFile);
  }

  /**
   * The classpath entries needed to run {@code com.google.gwt.dev.Compiler} to build the module
   * specified by {@link #modules}.
   */
  @VisibleForTesting
  Iterable<Path> getClasspathEntries(SourcePathResolver pathResolver) {
    ImmutableSet.Builder<Path> classpathEntries = ImmutableSet.builder();
    classpathEntries.addAll(pathResolver.getAllAbsolutePaths(gwtModuleJars));
    for (BuildRule dep : getDeclaredDeps()) {
      if (!(dep instanceof JavaLibrary)) {
        continue;
      }

      JavaLibrary javaLibrary = (JavaLibrary) dep;
      for (SourcePath path : javaLibrary.getOutputClasspaths()) {
        classpathEntries.add(pathResolver.getAbsolutePath(path));
      }
    }
    return classpathEntries.build();
  }
}
