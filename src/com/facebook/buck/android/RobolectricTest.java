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

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.ForkMode;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.Optionals;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class RobolectricTest extends JavaTest {

  private static final Logger LOG = Logger.get(RobolectricTest.class);

  private final AndroidPlatformTarget androidPlatformTarget;
  private final Optional<DummyRDotJava> optionalDummyRDotJava;
  private final Optional<SourcePath> robolectricManifest;
  private final Optional<SourcePath> robolectricRuntimeDependency;

  /**
   * Used by robolectric test runner to get list of resource directories that can be used for tests.
   */
  static final String LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME =
      "buck.robolectric_res_directories";

  static final String LIST_OF_ASSETS_DIRECTORIES_PROPERTY_NAME =
      "buck.robolectric_assets_directories";

  private static final String ROBOLECTRIC_MANIFEST = "buck.robolectric_manifest";

  private static final String ROBOLECTRIC_DEPENDENCY_DIR = "robolectric.dependency.dir";

  private final boolean passDirectoriesInFile;
  private final Path resourceDirectoriesPath;
  private final Path assetDirectoriesPath;

  protected RobolectricTest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      AndroidPlatformTarget androidPlatformTarget,
      JavaLibrary compiledTestsLibrary,
      Set<String> labels,
      Set<String> contacts,
      TestType testType,
      List<Arg> vmArgs,
      Map<String, String> nativeLibsEnvironment,
      Optional<DummyRDotJava> optionalDummyRDotJava,
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
            resolver ->
                optionalDummyRDotJava
                    .map(
                        dummyRDotJava ->
                            ImmutableList.of(
                                resolver.getAbsolutePath(dummyRDotJava.getSourcePathToOutput())))
                    .orElseGet(ImmutableList::of)),
        labels,
        contacts,
        testType,
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
    this.optionalDummyRDotJava = optionalDummyRDotJava;
    this.robolectricRuntimeDependency = robolectricRuntimeDependency;
    this.robolectricManifest = robolectricManifest;
    this.passDirectoriesInFile = passDirectoriesInFile;

    resourceDirectoriesPath = getResourceDirectoriesPath(projectFilesystem, buildTarget);
    assetDirectoriesPath = getAssetDirectoriesPath(projectFilesystem, buildTarget);
  }

  @VisibleForTesting
  static Path getResourceDirectoriesPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    return BuildTargetPaths.getGenPath(
        projectFilesystem, buildTarget, "%s/robolectric-resource-directories");
  }

  @VisibleForTesting
  static Path getAssetDirectoriesPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    return BuildTargetPaths.getGenPath(
        projectFilesystem, buildTarget, "%s/robolectric-asset-directories");
  }

  @Override
  protected ImmutableSet<Path> getBootClasspathEntries() {
    return ImmutableSet.copyOf(androidPlatformTarget.getBootclasspathEntries());
  }

  @Override
  protected void addPreTestSteps(
      BuildContext buildContext, ImmutableList.Builder<Step> stepsBuilder) {
    stepsBuilder.add(
        new WriteFileStep(
            getProjectFilesystem(),
            getDirectoriesContent(
                buildContext.getSourcePathResolver(), HasAndroidResourceDeps::getRes),
            resourceDirectoriesPath,
            false));
    stepsBuilder.add(
        new WriteFileStep(
            getProjectFilesystem(),
            getDirectoriesContent(
                buildContext.getSourcePathResolver(), HasAndroidResourceDeps::getAssets),
            assetDirectoriesPath,
            false));
  }

  @Override
  protected void onAmendVmArgs(
      ImmutableList.Builder<String> vmArgsBuilder,
      SourcePathResolver pathResolver,
      Optional<TargetDevice> targetDevice) {
    super.onAmendVmArgs(vmArgsBuilder, pathResolver, targetDevice);
    Preconditions.checkState(
        optionalDummyRDotJava.isPresent(), "DummyRDotJava must have been created!");
    vmArgsBuilder.add(
        getRobolectricResourceDirectoriesArg(
            pathResolver, optionalDummyRDotJava.get().getAndroidResourceDeps()));
    vmArgsBuilder.add(
        getRobolectricAssetsDirectories(
            pathResolver, optionalDummyRDotJava.get().getAndroidResourceDeps()));

    // Force robolectric to only use local dependency resolution.
    vmArgsBuilder.add("-Drobolectric.offline=true");
    robolectricManifest.ifPresent(
        s ->
            vmArgsBuilder.add(
                String.format("-D%s=%s", ROBOLECTRIC_MANIFEST, pathResolver.getAbsolutePath(s))));
    robolectricRuntimeDependency.ifPresent(
        s ->
            vmArgsBuilder.add(
                String.format(
                    "-D%s=%s", ROBOLECTRIC_DEPENDENCY_DIR, pathResolver.getAbsolutePath(s))));
  }

  @VisibleForTesting
  String getRobolectricResourceDirectoriesArg(
      SourcePathResolver pathResolver, List<HasAndroidResourceDeps> resourceDeps) {
    String argValue;
    if (passDirectoriesInFile) {
      argValue = "@" + resourceDirectoriesPath;
    } else {
      argValue =
          Joiner.on(File.pathSeparator)
              .join(
                  getDirs(resourceDeps.stream().map(HasAndroidResourceDeps::getRes), pathResolver));
    }

    return String.format("-D%s=%s", LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME, argValue);
  }

  @VisibleForTesting
  String getRobolectricAssetsDirectories(
      SourcePathResolver pathResolver, List<HasAndroidResourceDeps> resourceDeps) {
    String argValue;
    if (passDirectoriesInFile) {
      argValue = "@" + assetDirectoriesPath;
    } else {
      argValue =
          Joiner.on(File.pathSeparator)
              .join(
                  getDirs(
                      resourceDeps.stream().map(HasAndroidResourceDeps::getAssets), pathResolver));
    }

    return String.format("-D%s=%s", LIST_OF_ASSETS_DIRECTORIES_PROPERTY_NAME, argValue);
  }

  @Override
  public void onPreTest(BuildContext buildContext) throws IOException {
    getProjectFilesystem()
        .writeContentsToPath(
            getDirectoriesContent(
                buildContext.getSourcePathResolver(), HasAndroidResourceDeps::getRes),
            resourceDirectoriesPath);
    getProjectFilesystem()
        .writeContentsToPath(
            getDirectoriesContent(
                buildContext.getSourcePathResolver(), HasAndroidResourceDeps::getAssets),
            assetDirectoriesPath);
  }

  private String getDirectoriesContent(
      SourcePathResolver pathResolver, Function<HasAndroidResourceDeps, SourcePath> filter) {
    String content;
    if (optionalDummyRDotJava.isPresent()) {
      Iterable<String> resourceDirectories =
          getDirs(
              optionalDummyRDotJava.get().getAndroidResourceDeps().stream().map(filter),
              pathResolver);
      content = Joiner.on('\n').join(resourceDirectories);
    } else {
      content = "";
    }
    return content;
  }

  private Iterable<String> getDirs(
      Stream<SourcePath> sourcePathStream, SourcePathResolver pathResolver) {

    return sourcePathStream
        .filter(Objects::nonNull)
        .map(input -> getProjectFilesystem().relativize(pathResolver.getAbsolutePath(input)))
        .filter(
            input -> {
              try {
                if (!getProjectFilesystem().isDirectory(input)) {
                  throw new RuntimeException(
                      String.format(
                          "Path %s is needed to run robolectric test %s, but was not found.",
                          input, getBuildTarget()));
                }
                return !getProjectFilesystem().getDirectoryContents(input).isEmpty();
              } catch (IOException e) {
                LOG.warn(e, "Error filtering path for Robolectric res/assets.");
                return true;
              }
            })
        .map(Object::toString)
        .collect(Collectors.toList());
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return Stream.concat(
        // Inherit any runtime deps from `JavaTest`.
        super.getRuntimeDeps(ruleFinder),
        Stream.of(
                // On top of the runtime dependencies of a normal {@link JavaTest}, we need to make
                // the
                // {@link DummyRDotJava} and any of its resource deps is available locally (if it
                // exists)
                // to run this test.
                Optionals.toStream(optionalDummyRDotJava),
                Optionals.toStream(optionalDummyRDotJava)
                    .flatMap(input -> input.getAndroidResourceDeps().stream())
                    .flatMap(input -> Stream.of(input.getRes(), input.getAssets()))
                    .filter(Objects::nonNull)
                    .flatMap(ruleFinder.FILTER_BUILD_RULE_INPUTS),
                // It's possible that the user added some tool as a dependency, so make sure we
                // promote this rules first-order deps to runtime deps, so that these potential
                // tools are available when this test runs.
                getBuildDeps().stream())
            .reduce(Stream.empty(), Stream::concat)
            .map(BuildRule::getBuildTarget));
  }
}
