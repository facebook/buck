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

import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasDepsOverride;
import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * Buildable that produces a GWT application as a WAR file, which is a zip of the outputs produced
 * by the GWT compiler.
 */
public class GwtBinary extends AbstractBuildable implements HasDepsOverride {

  /**
   * Valid values for the GWT Compiler's {@code -style} flag.
   * Acceptable values are defined in the GWT docs at http://bit.ly/1sclx5O.
   */
  static enum Style {
    /** Named "obf" for "obfuscated". This is the default style. */
    OBF,
    PRETTY,
    DETAILED,
    ;
  }

  private static final String GWT_COMPILER_CLASS = "com.google.gwt.dev.Compiler";

  private final Path outputFile;
  private final ImmutableSortedSet<String> modules;
  private final ImmutableList<String> vmArgs;
  private final Style style;
  private final boolean draftCompile;
  private final int optimize;
  private final int localWorkers;
  private final boolean strict;
  private final ImmutableList<String> experimentalArgs;
  private final ImmutableSortedSet<BuildRule> originalDeps;
  private final ImmutableSortedSet<BuildRule> moduleDeps;

  /**
   * JAR files that contain GWT modules that must be included on the classpath when running the GWT
   * compiler.
   * <p>
   * Currently, this will be instantiated as a side-effect of
   * {@link #iKnowWhatIAmDoingAndIWillSpecifyAllTheDepsMyself()}. See the TODO(simons) in that
   * method.
   */
  private ImmutableSortedSet<Path> gwtModuleJars;

  /**
   * @param buildTarget of the rule responsible for this Buildable.
   * @param modules The GWT modules to build with the GWT compiler.
   * @param originalDeps The rules passed to the {@code deps} argument in the build file.
   * @param moduleDeps The rules passed to the {@code module_deps} argument in the build file.
   */
  GwtBinary(
      BuildTarget buildTarget,
      ImmutableSortedSet<String> modules,
      List<String> vmArgs,
      Style style,
      boolean draftCompile,
      int optimize,
      int localWorkers,
      boolean strict,
      List<String> experimentalArgs,
      ImmutableSortedSet<BuildRule> originalDeps,
      ImmutableSortedSet<BuildRule> moduleDeps) {
    this.outputFile = BuildTargets.getGenPath(
        buildTarget,
        "__gwt_binary_%s__/" + buildTarget.getShortName() + ".zip");
    this.modules = Preconditions.checkNotNull(modules);
    Preconditions.checkArgument(
        !modules.isEmpty(),
        "Must specify at least one module for %s.",
        buildTarget);
    this.vmArgs = ImmutableList.copyOf(vmArgs);
    this.style = Preconditions.checkNotNull(style);
    this.draftCompile = draftCompile;

    // No need for bounds-checking this int because GWT does it internally: http://bit.ly/1kFN5M7.
    this.optimize = optimize;

    Preconditions.checkArgument(localWorkers > 0,
        "localWorkers must be greater than zero: %d",
        localWorkers);
    this.localWorkers = localWorkers;

    this.strict = strict;
    this.experimentalArgs = ImmutableList.copyOf(experimentalArgs);
    this.originalDeps = Preconditions.checkNotNull(originalDeps);
    this.moduleDeps = Preconditions.checkNotNull(moduleDeps);
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  /**
   * By default, the total deps for {@link GwtBinaryDescription.Arg} would be the union of
   * {@link GwtBinaryDescription.Arg#moduleDeps} and {@link GwtBinaryDescription.Arg#deps}.
   * <p>
   * That is a superset of the deps that {@link GwtBinary} needs: instead of building every
   * {@link JavaLibrary} in {@link GwtBinaryDescription.Arg#moduleDeps}, {@link GwtBinary} needs
   * only to build the {@link JavaLibrary#GWT_MODULE_FLAVOR} for each {@link JavaLibrary} in
   * {@link GwtBinaryDescription.Arg#moduleDeps}. Doing so avoids many calls to {@code javac}.
   */
  @Override
  public ImmutableSortedSet<BuildRule> iKnowWhatIAmDoingAndIWillSpecifyAllTheDepsMyself(
      final BuildRuleResolver ruleResolver) {
    // TODO(simons): Make the BuildRuleResolver available in a Description so that this work can be
    // done in GwtBinaryDescription.
    final ImmutableSortedSet.Builder<BuildRule> totalDeps =
        ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(originalDeps);

    // Find all of the reachable JavaLibrary rules and grab their associated GwtModules.
    final ImmutableSortedSet.Builder<Path> gwtModuleJarsBuilder =
        ImmutableSortedSet.naturalOrder();
    new AbstractDependencyVisitor(moduleDeps) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (!(rule.getBuildable() instanceof JavaLibrary)) {
          return ImmutableSet.of();
        }

        JavaLibrary javaLibrary = (JavaLibrary) rule.getBuildable();
        BuildTarget gwtModuleTarget = BuildTargets.createFlavoredBuildTarget(
            javaLibrary, JavaLibrary.GWT_MODULE_FLAVOR);
        BuildRule gwtModule = ruleResolver.get(gwtModuleTarget);

        // Note that gwtModule could be null if javaLibrary is a rule with no srcs of its own,
        // but a rule that exists only as a collection of deps.
        if (gwtModule != null) {
          totalDeps.add(gwtModule);
          gwtModuleJarsBuilder.add(gwtModule.getBuildable().getPathToOutputFile());
        }

        // Traverse all of the deps of this rule.
        return rule.getDeps();
      }
    }.start();

    // Here, we abuse the fact that we know that iKnowWhatIAmDoingAndIWillSpecifyAllTheDepsMyself()
    // will be invoked before this.gwtModuleJars is read. Once this work can be done in
    // GwtBinaryDescription, we will no longer need this hack.
    this.gwtModuleJars = gwtModuleJarsBuilder.build();

    return totalDeps.build();
  };

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Create a clean directory where the .zip file will be written.
    Path workingDirectory = getPathToOutputFile().getParent();
    steps.add(new MakeCleanDirectoryStep(workingDirectory));

    // Write the deploy files into a separate directory so that the generated .zip is smaller.
    final Path deployDirectory = workingDirectory.resolve("deploy");
    steps.add(new MkdirStep(deployDirectory));

    Step javaStep = new ShellStep() {
      @Override
      public String getShortName() {
        return "gwt-compile";
      }

      @Override
      protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
        ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

        ImmutableList.Builder<String> javaArgsBuilder = ImmutableList.builder();
        javaArgsBuilder.add("java");
        javaArgsBuilder.add("-Dgwt.normalizeTimestamps=true");
        javaArgsBuilder.addAll(vmArgs);
        javaArgsBuilder.add(
            "-classpath", Joiner.on(File.pathSeparator).join(
                Iterables.transform(getClasspathEntries(), projectFilesystem.getAbsolutifier())),
            GWT_COMPILER_CLASS,
            "-war", projectFilesystem.resolve(getPathToOutputFile()).toString(),
            "-style", style.name(),
            "-optimize", String.valueOf(optimize),
            "-localWorkers", String.valueOf(localWorkers),
            "-deploy", projectFilesystem.resolve(deployDirectory).toString());
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

    buildableContext.recordArtifact(getPathToOutputFile());

    return steps.build();
  }

  @Override
  public Builder appendDetailsToRuleKey(Builder builder) {
    return builder
        .set("moduleDeps", moduleDeps)
        .set("modules", modules)
        .set("vmArgs", vmArgs)
        .set("style", style.name())
        .set("draftCompile", draftCompile)
        .set("optimize", optimize)
        .set("localWorkers", localWorkers)
        .set("strict", strict)
        .set("experimentalArgs", experimentalArgs);
  }

  /**
   * @return The {@code .zip} file produced by this rule.
   */
  @Override
  public Path getPathToOutputFile() {
    return outputFile;
  }

  /**
   * The classpath entries needed to run {@code com.google.gwt.dev.Compiler} to build the module
   * specified by {@link #modules}.
   */
  @VisibleForTesting
  Iterable<Path> getClasspathEntries() {
    ImmutableSet.Builder<Path> classpathEntries = ImmutableSet.builder();
    classpathEntries.addAll(gwtModuleJars);
    for (BuildRule dep : originalDeps) {
      if (!(dep.getBuildable() instanceof JavaLibrary)) {
        continue;
      }

      JavaLibrary javaLibrary = (JavaLibrary) dep.getBuildable();
      for (Path path : javaLibrary.getOutputClasspathEntries().values()) {
        classpathEntries.add(path);
      }
    }
    return classpathEntries.build();
  }
}
