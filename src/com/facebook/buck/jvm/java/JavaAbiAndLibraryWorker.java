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
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Contains implementation common to rules that invoke the Java compiler. */
public class JavaAbiAndLibraryWorker implements RuleKeyAppendable {
  private final ProjectFilesystem filesystem;
  private final SourcePathRuleFinder ruleFinder;

  private final CompileToJarStepFactory compileStepFactory;
  private final ImmutableSortedSet<SourcePath> srcs;
  private final ImmutableSortedSet<SourcePath> resources;

  private final Optional<Path> resourcesRoot;

  private final Optional<SourcePath> manifestFile;
  private final ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths;
  private final ImmutableSet<Pattern> classesToRemoveFromJar;

  @Nullable private final RuleOutputs abiOutputs;

  public JavaAbiAndLibraryWorker(
      BuildTarget target,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      CompileToJarStepFactory compileStepFactory,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      Optional<Path> resourcesRoot,
      Optional<SourcePath> manifestFile,
      ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths,
      ImmutableSet<Pattern> classesToRemoveFromJar) {
    this.filesystem = filesystem;
    this.ruleFinder = ruleFinder;
    this.compileStepFactory = compileStepFactory;
    this.srcs = srcs;
    this.resources = resources;
    this.resourcesRoot = resourcesRoot;
    this.manifestFile = manifestFile;
    this.compileTimeClasspathSourcePaths = compileTimeClasspathSourcePaths;
    this.classesToRemoveFromJar = classesToRemoveFromJar;

    BuildTarget libraryTarget =
        HasJavaAbi.isLibraryTarget(target) ? target : HasJavaAbi.getLibraryTarget(target);
    if (!srcs.isEmpty() || !resources.isEmpty() || manifestFile.isPresent()) {
      BuildTarget abiTarget = HasJavaAbi.getSourceAbiJar(libraryTarget);
      this.abiOutputs =
          new RuleOutputs(
              abiTarget,
              Optional.of(
                  BuildTargets.getGenPath(filesystem, abiTarget, "lib__%s__output")
                      .resolve(String.format("%s-abi.jar", abiTarget.getShortName()))));
    } else {
      this.abiOutputs = null;
    }
  }

  public ImmutableSortedSet<SourcePath> getSrcs() {
    return srcs;
  }

  public ImmutableSortedSet<SourcePath> getResources() {
    return resources;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("compileStepFactory", compileStepFactory)
        .setReflectively("srcs", srcs)
        .setReflectively("resources", resources)
        .setReflectively("resourcesRoot", String.valueOf(resourcesRoot))
        .setReflectively("manifestFile", manifestFile)
        .setReflectively("compileTimeClasspathSourcePaths", compileTimeClasspathSourcePaths)
        .setReflectively("classesToRemoveFromJar", classesToRemoveFromJar);
  }

  public RuleOutputs getAbiOutputs() {
    return Preconditions.checkNotNull(abiOutputs);
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
        ImmutableList.of(),
        compileTimeClasspathSourcePaths,
        false,
        null,
        compileStepFactory,
        resourcesRoot,
        manifestFile,
        classesToRemoveFromJar,
        steps);

    return steps.build();
  }

  public class RuleOutputs {
    private final BuildTarget target;
    private final Optional<Path> outputJarPath;
    private final HasJavaAbi.JarContentsSupplier jarContents;
    private final boolean isAbi;

    public RuleOutputs(BuildTarget target, Optional<Path> outputJarPath) {
      this.target = target;
      this.outputJarPath = outputJarPath;
      this.isAbi = HasJavaAbi.isSourceAbiTarget(target);
      jarContents =
          new HasJavaAbi.JarContentsSupplier(
              new SourcePathResolver(ruleFinder), getSourcePathToOutput());
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
      return JavaAbiAndLibraryWorker.this.buildLocally(
          this, buildContext, buildableContext, executionContext, stepRunner, service);
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
  }
}
