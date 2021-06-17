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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Stream;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class RobolectricTest extends JavaTest {
  private final AndroidPlatformTarget androidPlatformTarget;
  private final RobolectricTestHelper robolectricTestHelper;
  private final boolean includeBootClasspathInRequiredPaths;

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
      Set<Path> nativeLibsRequiredPaths,
      MergeAssets binaryResources,
      UnitTestOptions unitTestOptions,
      Optional<Long> testRuleTimeoutMs,
      Optional<Long> testCaseTimeoutMs,
      ImmutableMap<String, Arg> env,
      boolean runTestSeparately,
      ForkMode forkMode,
      Optional<Level> stdOutLogLevel,
      Optional<Level> stdErrLogLevel,
      ImmutableSortedSet<SourcePath> resources,
      ImmutableSet<Path> externalResourcesPaths,
      Optional<SourcePath> robolectricRuntimeDependency,
      ImmutableSortedSet<BuildRule> robolectricRuntimeDependencies,
      Optional<RobolectricRuntimeDependencies> robolectricRuntimeDependenciesRule,
      SourcePath robolectricManifest,
      Tool javaRuntimeLauncher,
      OptionalInt javaRuntimeVersion,
      boolean includeBootClasspathInRequiredPaths,
      boolean useRelativePathsInClasspathFile,
      boolean withDownwardApi,
      boolean useDependencyOrderClasspath) {
    super(
        buildTarget,
        projectFilesystem,
        buildRuleParams,
        compiledTestsLibrary,
        Optional.of(
            resolver -> {
              ImmutableList.Builder<Path> builder = ImmutableList.builder();
              builder.add(
                  resolver.getAbsolutePath(unitTestOptions.getSourcePathToOutput()).getPath());

              return builder.build();
            }),
        labels,
        contacts,
        testType,
        targetLevel,
        javaRuntimeLauncher,
        javaRuntimeVersion,
        vmArgs,
        nativeLibsEnvironment,
        nativeLibsRequiredPaths,
        testRuleTimeoutMs,
        testCaseTimeoutMs,
        env,
        runTestSeparately,
        forkMode,
        stdOutLogLevel,
        stdErrLogLevel,
        resources,
        useRelativePathsInClasspathFile,
        withDownwardApi,
        useDependencyOrderClasspath);
    this.androidPlatformTarget = androidPlatformTarget;
    this.robolectricTestHelper =
        new RobolectricTestHelper(
            binaryResources,
            externalResourcesPaths,
            robolectricRuntimeDependency,
            robolectricRuntimeDependencies,
            robolectricRuntimeDependenciesRule,
            robolectricManifest,
            getProjectFilesystem());
    this.includeBootClasspathInRequiredPaths = includeBootClasspathInRequiredPaths;
  }

  @Override
  protected ImmutableSet<Path> getBootClasspathEntries() {
    return androidPlatformTarget.getBootclasspathEntries().stream()
        .map(AbsPath::getPath)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  protected boolean includeBootClasspathInRequiredPaths() {
    return includeBootClasspathInRequiredPaths;
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
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return Stream.concat(
        // Inherit any runtime deps from `JavaTest`.
        super.getRuntimeDeps(buildRuleResolver),
        robolectricTestHelper.getExtraRuntimeDeps(getBuildDeps()));
  }

  @Override
  protected ImmutableSet<Path> getExtraRequiredPaths(
      SourcePathResolverAdapter sourcePathResolverAdapter) {
    return Stream.concat(
            super.getExtraRequiredPaths(sourcePathResolverAdapter).stream(),
            robolectricTestHelper.getExtraRequiredPaths(sourcePathResolverAdapter).stream())
        .collect(ImmutableSet.toImmutableSet());
  }
}
