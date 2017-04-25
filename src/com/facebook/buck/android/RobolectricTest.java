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

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;
import static com.facebook.buck.rules.BuildableProperties.Kind.TEST;

import com.facebook.buck.jvm.java.ForkMode;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.util.Optionals;
import com.google.common.annotations.VisibleForTesting;
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

  private static final BuildableProperties PROPERTIES =
      new BuildableProperties(ANDROID, LIBRARY, TEST);

  private final SourcePathRuleFinder ruleFinder;
  private final Optional<DummyRDotJava> optionalDummyRDotJava;
  private final Optional<SourcePath> robolectricManifest;
  private final Optional<String> robolectricRuntimeDependency;

  /**
   * Used by robolectric test runner to get list of resource directories that can be used for tests.
   */
  static final String LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME =
      "buck.robolectric_res_directories";

  static final String LIST_OF_ASSETS_DIRECTORIES_PROPERTY_NAME =
      "buck.robolectric_assets_directories";

  private static final String ROBOLECTRIC_MANIFEST = "buck.robolectric_manifest";

  private static final String ROBOLECTRIC_DEPENDENCY_DIR = "robolectric.dependency.dir";

  private final Function<DummyRDotJava, ImmutableSet<BuildRule>> resourceRulesFunction =
      input -> {
        ImmutableSet.Builder<BuildRule> resourceDeps = ImmutableSet.builder();
        for (HasAndroidResourceDeps hasAndroidResourceDeps : input.getAndroidResourceDeps()) {
          SourcePath resSourcePath = hasAndroidResourceDeps.getRes();
          if (resSourcePath == null) {
            continue;
          }
          Optionals.addIfPresent(getRuleFinder().getRule(resSourcePath), resourceDeps);
        }
        return resourceDeps.build();
      };

  private final Function<DummyRDotJava, ImmutableSet<BuildRule>> assetsRulesFunction =
      input -> {
        ImmutableSet.Builder<BuildRule> assetsDeps = ImmutableSet.builder();
        for (HasAndroidResourceDeps hasAndroidResourceDeps : input.getAndroidResourceDeps()) {
          SourcePath assetsSourcePath = hasAndroidResourceDeps.getAssets();
          if (assetsSourcePath == null) {
            continue;
          }
          Optionals.addIfPresent(getRuleFinder().getRule(assetsSourcePath), assetsDeps);
        }
        return assetsDeps.build();
      };

  protected RobolectricTest(
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      JavaLibrary compiledTestsLibrary,
      Set<String> labels,
      Set<String> contacts,
      TestType testType,
      JavaOptions javaOptions,
      List<String> vmArgs,
      Map<String, String> nativeLibsEnvironment,
      Optional<DummyRDotJava> optionalDummyRDotJava,
      Optional<Long> testRuleTimeoutMs,
      Optional<Long> testCaseTimeoutMs,
      ImmutableMap<String, String> env,
      boolean runTestSeparately,
      ForkMode forkMode,
      Optional<Level> stdOutLogLevel,
      Optional<Level> stdErrLogLevel,
      Optional<String> robolectricRuntimeDependency,
      Optional<SourcePath> robolectricManifest) {
    super(
        buildRuleParams,
        new SourcePathResolver(ruleFinder),
        compiledTestsLibrary,
        optionalDummyRDotJava
            .map(
                r ->
                    ImmutableSet.<Either<SourcePath, Path>>of(
                        Either.ofLeft(r.getSourcePathToOutput())))
            .orElse(ImmutableSet.of()),
        labels,
        contacts,
        testType,
        javaOptions.getJavaRuntimeLauncher(),
        vmArgs,
        nativeLibsEnvironment,
        testRuleTimeoutMs,
        testCaseTimeoutMs,
        env,
        runTestSeparately,
        forkMode,
        stdOutLogLevel,
        stdErrLogLevel);
    this.ruleFinder = ruleFinder;
    this.optionalDummyRDotJava = optionalDummyRDotJava;
    this.robolectricRuntimeDependency = robolectricRuntimeDependency;
    this.robolectricManifest = robolectricManifest;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  protected ImmutableSet<Path> getBootClasspathEntries(ExecutionContext context) {
    return ImmutableSet.copyOf(context.getAndroidPlatformTarget().getBootclasspathEntries());
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
        getRobolectricResourceDirectories(
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
        s -> vmArgsBuilder.add(String.format("-D%s=%s", ROBOLECTRIC_DEPENDENCY_DIR, s)));
  }

  @VisibleForTesting
  String getRobolectricResourceDirectories(
      SourcePathResolver pathResolver, List<HasAndroidResourceDeps> resourceDeps) {

    String resourceDirectories =
        getDirs(resourceDeps.stream().map(HasAndroidResourceDeps::getRes), pathResolver);

    return String.format(
        "-D%s=%s", LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME, resourceDirectories);
  }

  @VisibleForTesting
  String getRobolectricAssetsDirectories(
      SourcePathResolver pathResolver, List<HasAndroidResourceDeps> resourceDeps) {

    String assetsDirectories =
        getDirs(resourceDeps.stream().map(HasAndroidResourceDeps::getAssets), pathResolver);

    return String.format("-D%s=%s", LIST_OF_ASSETS_DIRECTORIES_PROPERTY_NAME, assetsDirectories);
  }

  private String getDirs(Stream<SourcePath> sourcePathStream, SourcePathResolver pathResolver) {

    return sourcePathStream
        .filter(Objects::nonNull)
        .map(pathResolver::getRelativePath)
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
        .collect(Collectors.joining(File.pathSeparator));
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    return Stream.concat(
        // Inherit any runtime deps from `JavaTest`.
        super.getRuntimeDeps(),
        Stream.of(
                // On top of the runtime dependencies of a normal {@link JavaTest}, we need to make the
                // {@link DummyRDotJava} and any of its resource deps is available locally (if it exists)
                // to run this test.
                OptionalCompat.asSet(optionalDummyRDotJava).stream(),
                optionalDummyRDotJava.map(resourceRulesFunction).orElse(ImmutableSet.of()).stream(),
                optionalDummyRDotJava.map(assetsRulesFunction).orElse(ImmutableSet.of()).stream(),
                // It's possible that the user added some tool as a dependency, so make sure we
                // promote this rules first-order deps to runtime deps, so that these potential
                // tools are available when this test runs.
                getBuildDeps().stream())
            .reduce(Stream.empty(), Stream::concat)
            .map(BuildRule::getBuildTarget));
  }

  public SourcePathRuleFinder getRuleFinder() {
    return ruleFinder;
  }
}
