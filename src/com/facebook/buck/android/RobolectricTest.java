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

import com.facebook.buck.java.JavaTest;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.TestType;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TargetDevice;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class RobolectricTest extends JavaTest {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(
      ANDROID, LIBRARY, TEST);

  private final Optional<DummyRDotJava> optionalDummyRDotJava;
  /**
   * Used by robolectric test runner to get list of resource directories that
   * can be used for tests.
   */
  static final String LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME =
      "buck.robolectric_res_directories";

  private final Function<HasAndroidResourceDeps, String> resourceDirectoryFunction =
      new Function<HasAndroidResourceDeps, String>() {
    @Override
    public String apply(HasAndroidResourceDeps input) {
      return Optional.fromNullable(input.getRes())
          .transform(getResolver().getPathFunction())
          .get()
          .toString();
    }
  };

  protected RobolectricTest(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Set<SourcePath> srcs,
      Set<SourcePath> resources,
      Set<Label> labels,
      Set<String> contacts,
      Optional<SourcePath> proguardConfig,
      ImmutableSet<Path> additionalClasspathEntries,
      JavacOptions javacOptions,
      List<String> vmArgs,
      ImmutableSet<BuildRule> sourceTargetsUnderTest,
      Optional<Path> resourcesRoot,
      Optional<DummyRDotJava> optionalDummyRDotJava,
      Optional<Long> testRuleTimeoutMs,
      boolean runTestSeparately) {
    super(
        buildRuleParams,
        resolver,
        srcs,
        resources,
        labels,
        contacts,
        proguardConfig,
        additionalClasspathEntries,
        TestType.JUNIT,
        javacOptions,
        vmArgs,
        sourceTargetsUnderTest,
        resourcesRoot,
        testRuleTimeoutMs,
        runTestSeparately);
    this.optionalDummyRDotJava = optionalDummyRDotJava;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  protected ImmutableSet<Path> getBootClasspathEntries(ExecutionContext context) {
    return FluentIterable.from(context.getAndroidPlatformTarget().getBootclasspathEntries())
        .toSet();
  }

  @Override
  protected void onAmendVmArgs(ImmutableList.Builder<String> vmArgsBuilder,
      Optional<TargetDevice> targetDevice) {
    super.onAmendVmArgs(vmArgsBuilder, targetDevice);
    Preconditions.checkState(optionalDummyRDotJava.isPresent(),
        "DummyRDotJava must have been created!");
    vmArgsBuilder.add(getRobolectricResourceDirectories(
        optionalDummyRDotJava.get().getAndroidResourceDeps()));

    // Force robolectric to only use local dependency resolution.
    vmArgsBuilder.add("-Drobolectric.offline=true");
  }

  @VisibleForTesting
  String getRobolectricResourceDirectories(List<HasAndroidResourceDeps> resourceDeps) {
    String resourceDirectories = Joiner.on(File.pathSeparator)
        .join(Iterables.transform(resourceDeps, resourceDirectoryFunction));

    return String.format("-D%s=%s",
        LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME,
        resourceDirectories);
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        // Inherit any runtime deps from `JavaTest`.
        .addAll(super.getRuntimeDeps())
        // On top of the runtime dependencies of a normal {@link JavaTest}, we need to make the
        // {@link DummyRDotJava} is available locally, if it exists, to run this test.
        .addAll(Optional.presentInstances(ImmutableList.of(optionalDummyRDotJava)))
        // It's possible that the user added some tool as a dependency, so make sure we promote
        // this rules first-order deps to runtime deps, so that these potential tools are available
        // when this test runs.
        .addAll(getDeps())
        .build();
  }

}
