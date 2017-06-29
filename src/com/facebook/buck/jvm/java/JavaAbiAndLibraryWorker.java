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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepRunner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Contains implementation common to rules that invoke the Java compiler. */
public class JavaAbiAndLibraryWorker implements RuleKeyAppendable {
  private final ProjectFilesystem filesystem;
  private final SourcePathRuleFinder ruleFinder;

  private final boolean trackClassUsage;
  private final CompileToJarStepFactory compileStepFactory;
  private final ImmutableSortedSet<SourcePath> srcs;
  private final ImmutableSortedSet<SourcePath> resources;

  private final Optional<Path> resourcesRoot;

  private final Optional<SourcePath> manifestFile;
  private final ImmutableList<String> postprocessClassesCommands;
  private final ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths;
  private final ZipArchiveDependencySupplier abiClasspath;
  private final ImmutableSet<Pattern> classesToRemoveFromJar;

  @Nullable private final RuleOutputs abiOutputs;
  private final RuleOutputs libraryOutputs;

  @Nullable private final Path depFileRelativePath;

  public JavaAbiAndLibraryWorker(
      BuildTarget target,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      boolean trackClassUsage,
      CompileToJarStepFactory compileStepFactory,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      ImmutableList<String> postprocessClassesCommands,
      Optional<Path> resourcesRoot,
      Optional<SourcePath> manifestFile,
      ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths,
      ZipArchiveDependencySupplier abiClasspath,
      ImmutableSet<Pattern> classesToRemoveFromJar) {
    this.filesystem = filesystem;
    this.ruleFinder = ruleFinder;
    this.trackClassUsage = trackClassUsage;
    this.compileStepFactory = compileStepFactory;
    this.srcs = srcs;
    this.resources = resources;
    this.postprocessClassesCommands = postprocessClassesCommands;
    this.resourcesRoot = resourcesRoot;
    this.manifestFile = manifestFile;
    this.compileTimeClasspathSourcePaths = compileTimeClasspathSourcePaths;
    this.abiClasspath = abiClasspath;
    this.classesToRemoveFromJar = classesToRemoveFromJar;

    depFileRelativePath = trackClassUsage ? getUsedClassesFilePath(target, filesystem) : null;

    BuildTarget libraryTarget =
        HasJavaAbi.isLibraryTarget(target) ? target : HasJavaAbi.getLibraryTarget(target);
    if (!srcs.isEmpty() || !resources.isEmpty() || manifestFile.isPresent()) {
      this.libraryOutputs =
          new RuleOutputs(
              libraryTarget, Optional.of(DefaultJavaLibrary.getOutputJarPath(target, filesystem)));
      if (!srcs.isEmpty() && depFileRelativePath != null) {
        libraryOutputs.addArtifact(depFileRelativePath);
      }
      BuildTarget abiTarget = HasJavaAbi.getSourceAbiJar(libraryTarget);
      this.abiOutputs =
          new RuleOutputs(
              abiTarget,
              Optional.of(
                  BuildTargets.getGenPath(filesystem, abiTarget, "lib__%s__output")
                      .resolve(String.format("%s-abi.jar", abiTarget.getShortName()))));
    } else {
      this.libraryOutputs = new RuleOutputs(libraryTarget, Optional.empty());
      this.abiOutputs = null;
    }

    Path pathToClassHashes = JavaLibraryRules.getPathToClassHashes(libraryTarget, filesystem);
    libraryOutputs.addArtifact(pathToClassHashes);
  }

  public ImmutableSortedSet<SourcePath> getSrcs() {
    return srcs;
  }

  public ImmutableSortedSet<SourcePath> getResources() {
    return resources;
  }

  public boolean getTrackClassUsage() {
    return trackClassUsage;
  }

  @Nullable
  public Path getDepFileRelativePath() {
    return depFileRelativePath;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("compileStepFactory", compileStepFactory)
        .setReflectively("srcs", srcs)
        .setReflectively("resources", resources)
        .setReflectively("postprocessClassesCommands", postprocessClassesCommands)
        .setReflectively("resourcesRoot", String.valueOf(resourcesRoot))
        .setReflectively("manifestFile", manifestFile)
        .setReflectively("abiClasspath", abiClasspath)
        .setReflectively("classesToRemoveFromJar", classesToRemoveFromJar);
  }

  public RuleOutputs getAbiOutputs() {
    return Preconditions.checkNotNull(abiOutputs);
  }

  public RuleOutputs getLibraryOutputs() {
    return libraryOutputs;
  }

  static Path getUsedClassesFilePath(BuildTarget target, ProjectFilesystem filesystem) {
    return DefaultJavaLibrary.getOutputJarDirPath(target, filesystem).resolve("used-classes.json");
  }

  public ListenableFuture<Void> buildLocally(
      RuleOutputs ruleOutputs,
      BuildContext buildContext,
      BuildableContext buildableContext,
      ExecutionContext executionContext,
      StepRunner stepRunner,
      ListeningExecutorService service) {
    if (ruleOutputs.isAbi()) {
      ((JavacToJarStepFactory) compileStepFactory)
          .setCompileAbi(filesystem.resolve(ruleOutputs.getOutputJar().get()));
    }

    return stepRunner.runStepsForBuildTarget(
        executionContext,
        () -> getBuildSteps(buildContext, buildableContext, ruleOutputs),
        Optional.of(ruleOutputs.getTarget()),
        service);
  }

  private ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext, RuleOutputs ruleOutputs) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    JavaLibraryRules.addCompileToJarSteps(
        ruleOutputs.getTarget(),
        filesystem,
        context,
        buildableContext,
        ruleOutputs.getOutputJar(),
        ruleFinder,
        srcs,
        resources,
        ruleOutputs.isAbi() ? ImmutableList.of() : postprocessClassesCommands,
        compileTimeClasspathSourcePaths,
        !ruleOutputs.isAbi() && trackClassUsage,
        ruleOutputs.isAbi() ? null : depFileRelativePath,
        compileStepFactory,
        resourcesRoot,
        manifestFile,
        classesToRemoveFromJar,
        steps);

    if (!ruleOutputs.isAbi()) {
      JavaLibraryRules.addAccumulateClassNamesStep(
          ruleOutputs.getTarget(), filesystem, ruleOutputs.getSourcePathToOutput(), context, steps);
    }

    return steps.build();
  }

  public class RuleOutputs {
    private final BuildTarget target;
    private final Optional<Path> outputJarPath;
    private final HasJavaAbi.JarContentsSupplier jarContents;
    private final boolean isAbi;
    private final List<Path> artifactsToRecord = new ArrayList<>();

    public RuleOutputs(BuildTarget target, Optional<Path> outputJarPath) {
      this.target = target;
      this.outputJarPath = outputJarPath;
      this.isAbi = HasJavaAbi.isSourceAbiTarget(target);
      jarContents =
          new HasJavaAbi.JarContentsSupplier(
              new SourcePathResolver(ruleFinder), getSourcePathToOutput());
      outputJarPath.ifPresent(artifactsToRecord::add);
    }

    public boolean isAbi() {
      return isAbi;
    }

    public BuildTarget getTarget() {
      return target;
    }

    public ListenableFuture<Void> buildLocally(
        BuildContext buildContext,
        BuildableContext buildableContext,
        ExecutionContext executionContext,
        StepRunner stepRunner,
        ListeningExecutorService service) {
      recordArtifacts(buildableContext);

      return JavaAbiAndLibraryWorker.this.buildLocally(
          this, buildContext, buildableContext, executionContext, stepRunner, service);
    }

    protected void recordArtifacts(BuildableContext buildableContext) {
      artifactsToRecord.forEach(buildableContext::recordArtifact);
    }

    public void addArtifact(Path artifact) {
      artifactsToRecord.add(artifact);
    }

    public Optional<Path> getOutputJar() {
      return outputJarPath;
    }

    @Nullable
    public SourcePath getSourcePathToOutput() {
      return outputJarPath.map(o -> new ExplicitBuildTargetSourcePath(target, o)).orElse(null);
    }

    public ImmutableSortedSet<SourcePath> getJarContents() {
      return jarContents.get();
    }

    public void initializeFromDisk() throws IOException {
      jarContents.load();
    }

    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      return JavaAbiAndLibraryWorker.this.getBuildSteps(context, buildableContext, this);
    }
  }
}
