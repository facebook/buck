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

package com.facebook.buck.android;

import com.facebook.buck.android.device.TargetDevice;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.ForkMode;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Stream;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class RobolectricTest extends JavaTest {

  private final AndroidPlatformTarget androidPlatformTarget;
  private final RobolectricTestHelper robolectricTestHelper;

  protected RobolectricTest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      AndroidPlatformTarget androidPlatformTarget,
      JavaLibrary compiledTestsLibrary,
      Set<String> labels,
      Set<String> contacts,
      TestType testType,
      String targetLevel,
      List<Arg> vmArgs,
      Map<String, String> nativeLibsEnvironment,
      Optional<DummyRDotJava> optionalDummyRDotJava,
      Optional<UnitTestOptions> unitTestOptions,
      Optional<Long> testRuleTimeoutMs,
      Optional<Long> testCaseTimeoutMs,
      ImmutableMap<String, Arg> env,
      boolean runTestSeparately,
      ForkMode forkMode,
      Optional<Level> stdOutLogLevel,
      Optional<Level> stdErrLogLevel,
      Optional<SourcePath> unbundledResourcesRoot,
      Optional<SourcePath> robolectricRuntimeDependency,
      Optional<SourcePath> robolectricManifest,
      boolean passDirectoriesInFile,
      Tool javaRuntimeLauncher) {
    super(
        buildTarget,
        projectFilesystem,
        buildRuleParams,
        compiledTestsLibrary,
        Optional.of(
            resolver -> {
              ImmutableList.Builder<Path> builder = ImmutableList.builder();
              optionalDummyRDotJava.ifPresent(
                  dummyRDotJava ->
                      builder.add(resolver.getAbsolutePath(dummyRDotJava.getSourcePathToOutput())));
              unitTestOptions.ifPresent(
                  options ->
                      builder.add(resolver.getAbsolutePath(options.getSourcePathToOutput())));
              return builder.build();
            }),
        labels,
        contacts,
        testType,
        targetLevel,
        javaRuntimeLauncher,
        vmArgs,
        nativeLibsEnvironment,
        testRuleTimeoutMs,
        testCaseTimeoutMs,
        env,
        runTestSeparately,
        forkMode,
        stdOutLogLevel,
        stdErrLogLevel,
        unbundledResourcesRoot);
    this.androidPlatformTarget = androidPlatformTarget;
    this.robolectricTestHelper =
        new RobolectricTestHelper(
            getBuildTarget(),
            optionalDummyRDotJava,
            robolectricRuntimeDependency,
            robolectricManifest,
            getProjectFilesystem(),
            passDirectoriesInFile);
  }

  @Override
  protected ImmutableSet<Path> getBootClasspathEntries() {
    return ImmutableSet.copyOf(androidPlatformTarget.getBootclasspathEntries());
  }

  @Override
  protected void addPreTestSteps(
      BuildContext buildContext, ImmutableList.Builder<Step> stepsBuilder) {
    robolectricTestHelper.addPreTestSteps(buildContext, stepsBuilder);
  }

  @Override
  protected void onAmendVmArgs(
      ImmutableList.Builder<String> vmArgsBuilder,
      SourcePathResolverAdapter pathResolver,
      Optional<TargetDevice> targetDevice) {
    super.onAmendVmArgs(vmArgsBuilder, pathResolver, targetDevice);
    robolectricTestHelper.amendVmArgs(vmArgsBuilder, pathResolver);
  }

  @Override
  public void onPreTest(BuildContext buildContext) throws IOException {
    robolectricTestHelper.onPreTest(buildContext);
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return Stream.concat(
        // Inherit any runtime deps from `JavaTest`.
        super.getRuntimeDeps(buildRuleResolver),
        robolectricTestHelper.getExtraRuntimeDeps(buildRuleResolver, getBuildDeps()));
  }

  @VisibleForTesting
  RobolectricTestHelper getRobolectricTestHelper() {
    return robolectricTestHelper;
  }
}
