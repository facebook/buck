/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.ExportDependencies;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.test.rule.CoercedTestRunnerSpec;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.TestXRule;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * The new Java Test rule that uses the test protocol to run.
 *
 * <p>It cannot be run via buck's internal runners
 */
public class JavaTestX extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements TestXRule, HasRuntimeDeps, ExternalTestRunnerRule, ExportDependencies {

  private final JavaBinary compiledTestsBinary;
  private final JavaLibrary compiledTestsLibrary;

  private final ImmutableSet<String> labels;

  private final ImmutableSet<String> contacts;

  private final CoercedTestRunnerSpec specs;

  private final ImmutableList<Arg> vmArgs;

  private final ExplicitBuildTargetSourcePath classPathOutput;

  private final ExplicitBuildTargetSourcePath classPathFileOutput;

  private final ExplicitBuildTargetSourcePath jvmArgsOutput;

  public JavaTestX(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      JavaBinary compiledTestsBinary,
      JavaLibrary compiledTestsLibrary,
      Set<String> labels,
      Set<String> contacts,
      CoercedTestRunnerSpec specs,
      List<Arg> vmArg) {
    super(buildTarget, projectFilesystem, params);
    this.compiledTestsBinary = compiledTestsBinary;
    this.compiledTestsLibrary = compiledTestsLibrary;
    this.labels = ImmutableSet.copyOf(labels);
    this.contacts = ImmutableSet.copyOf(contacts);
    this.specs = specs;
    this.vmArgs = ImmutableList.copyOf(vmArg);
    this.classPathOutput =
        ExplicitBuildTargetSourcePath.of(
            buildTarget, BuildPaths.getGenDir(projectFilesystem, buildTarget).resolve("classname"));
    this.classPathFileOutput =
        ExplicitBuildTargetSourcePath.of(
            buildTarget,
            BuildPaths.getGenDir(projectFilesystem, buildTarget).resolve("classpath-file"));
    this.jvmArgsOutput =
        ExplicitBuildTargetSourcePath.of(
            buildTarget, BuildPaths.getGenDir(projectFilesystem, buildTarget).resolve("jvm-args"));
  }

  @Override
  public ImmutableSet<String> getLabels() {
    return labels;
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "__java_test_%s_output__");
  }

  private Path getClassPathFile() {
    return this.classPathFileOutput.getResolvedPath();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    buildableContext.recordArtifact(classPathOutput.getResolvedPath());
    buildableContext.recordArtifact(getClassPathFile());
    buildableContext.recordArtifact(jvmArgsOutput.getResolvedPath());

    return ImmutableList.of(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(),
                getProjectFilesystem(),
                classPathOutput.getResolvedPath().getParent())),
        new WriteFileStep(
            getProjectFilesystem(),
            () ->
                String.join(
                    System.lineSeparator(),
                    new CompiledClassFileFinder(
                            compiledTestsLibrary, buildContext.getSourcePathResolver())
                        .getClassNamesForSources()),
            classPathOutput.getResolvedPath(),
            false),
        new WriteFileStep(
            getProjectFilesystem(),
            () ->
                String.join(
                    System.lineSeparator(), getJvmArgs(buildContext.getSourcePathResolver())),
            jvmArgsOutput.getResolvedPath(),
            false),
        new AbstractExecutionStep("write classpath file") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {
            ImmutableSet<Path> classpathEntries = getRuntimeClasspath(buildContext);
            getProjectFilesystem()
                .writeLinesToPath(
                    Iterables.transform(classpathEntries, Object::toString), getClassPathFile());
            return StepExecutionResults.SUCCESS;
          }
        });
  }

  @Override
  public SortedSet<BuildRule> getExportedDeps() {
    return ImmutableSortedSet.of(compiledTestsLibrary);
  }

  @Override
  public SortedSet<BuildRule> getExportedProvidedDeps() {
    return ImmutableSortedSet.of();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    SourcePath output = compiledTestsBinary.getSourcePathToOutput();
    if (output == null) {
      return null;
    }
    return ForwardingBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return Stream.concat(
            // By the end of the build, all the transitive Java library dependencies *must* be
            // available on disk, so signal this requirement via the {@link HasRuntimeDeps}
            // interface.
            compiledTestsBinary.getTransitiveClasspathDeps().stream(),
            // It's possible that the user added some tool as a dependency, so make sure we promote
            // this rules first-order deps to runtime deps, so that these potential tools are
            // available when this test runs.
            getBuildDeps().stream())
        .map(BuildRule::getBuildTarget);
  }

  /**
   * @return a set of paths to the files which must be passed as the classpath to the java process
   *     when this test is executed
   */
  protected ImmutableSet<Path> getRuntimeClasspath(BuildContext buildContext) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    return builder
        .addAll(
            compiledTestsLibrary.getTransitiveClasspaths().stream()
                .map(buildContext.getSourcePathResolver()::getAbsolutePath)
                .collect(ImmutableSet.toImmutableSet()))
        .build();
  }

  /** @return a list of JVM args that should be passed to JVM to run the command properly */
  protected ImmutableList<String> getJvmArgs(SourcePathResolverAdapter sourcePathResolverAdapter) {
    return ImmutableList.copyOf(Arg.stringify(this.vmArgs, sourcePathResolverAdapter));
  }

  @Override
  public CoercedTestRunnerSpec getSpecs() {
    return specs;
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToSupplementaryOutput(String name) {
    if (name.equals("testbin")) {
      return getSourcePathToOutput();
    }
    if (name.equals("classnames")) {
      return classPathOutput;
    }
    if (name.equals("classpath-file")) {
      return classPathFileOutput;
    }
    if (name.equals("jvm-args")) {
      return jvmArgsOutput;
    }
    return null;
  }
}
