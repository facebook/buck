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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeFalse;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.jvm.java.KeystoreDescription;
import com.facebook.buck.jvm.java.KeystoreDescriptionArg;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class DuplicateResourcesTest {
  private BuildTarget mainResTarget;
  private BuildTarget directDepResTarget;
  private BuildTarget transitiveDepResTarget;
  private BuildTarget transitiveDepLibTarget;
  private BuildTarget bottomDepResTarget;
  private BuildTarget androidLibraryTarget;
  private BuildTarget androidBinaryTarget;
  private BuildTarget keystoreTarget;

  private FakeProjectFilesystem filesystem;

  private TargetNode<AndroidResourceDescriptionArg, AndroidResourceDescription> mainRes;
  private TargetNode<AndroidResourceDescriptionArg, AndroidResourceDescription> directDepRes;
  private TargetNode<AndroidResourceDescriptionArg, AndroidResourceDescription> transitiveDepRes;
  private TargetNode<AndroidResourceDescriptionArg, AndroidResourceDescription> bottomDepRes;
  private TargetNode<AndroidLibraryDescriptionArg, AndroidLibraryDescription> transitiveDepLib;
  private TargetNode<AndroidLibraryDescriptionArg, AndroidLibraryDescription> library;
  private TargetNode<KeystoreDescriptionArg, KeystoreDescription> keystore;

  /*
   * Builds up the following dependency graph, which an android_binary can depend on how it likes:
   *
   *   //main_app:res        //direct_dep:library <--------+//direct_dep:res
   *                                  ^                             ^
   *                                  |                             |
   *                                  +                             +
   *                         //transitive_dep:library<-----+//transitive_dep:res
   *                                                                ^
   *                                                                |
   *                                                                +
   *                                                        //bottom_dep:res
   */
  @Before
  public void makeRules() throws Exception {
    mainResTarget = BuildTargetFactory.newInstance("//main_app:res");
    directDepResTarget = BuildTargetFactory.newInstance("//direct_dep:res");
    transitiveDepResTarget = BuildTargetFactory.newInstance("//transitive_dep:res");
    transitiveDepLibTarget = BuildTargetFactory.newInstance("//transitive_dep:library");
    bottomDepResTarget = BuildTargetFactory.newInstance("//bottom_dep:res");
    androidLibraryTarget = BuildTargetFactory.newInstance("//direct_dep:library");
    androidBinaryTarget = BuildTargetFactory.newInstance("//main_app:binary");
    keystoreTarget = BuildTargetFactory.newInstance("//main_app:keystore");

    filesystem = new FakeProjectFilesystem();

    mainRes =
        AndroidResourceBuilder.createBuilder(mainResTarget)
            .setRes(new FakeSourcePath(filesystem, "main_app/res"))
            .setRDotJavaPackage("package")
            .build();

    directDepRes =
        AndroidResourceBuilder.createBuilder(directDepResTarget)
            .setRes(new FakeSourcePath(filesystem, "direct_dep/res"))
            .setRDotJavaPackage("package")
            .setDeps(ImmutableSortedSet.of(transitiveDepResTarget, transitiveDepLibTarget))
            .build();

    transitiveDepLib =
        AndroidLibraryBuilder.createBuilder(transitiveDepLibTarget)
            .addDep(transitiveDepResTarget)
            .build();

    transitiveDepRes =
        AndroidResourceBuilder.createBuilder(transitiveDepResTarget)
            .setRes(new FakeSourcePath(filesystem, "transitive_dep/res"))
            .setRDotJavaPackage("package")
            .setDeps(ImmutableSortedSet.of(bottomDepResTarget))
            .build();

    bottomDepRes =
        AndroidResourceBuilder.createBuilder(bottomDepResTarget)
            .setRes(new FakeSourcePath(filesystem, "bottom_dep/res"))
            .setRDotJavaPackage("package")
            .build();

    library =
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addDep(directDepResTarget)
            .addDep(transitiveDepLibTarget)
            .build();

    keystore =
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(new FakeSourcePath(filesystem, "store"))
            .setProperties(new FakeSourcePath(filesystem, "properties"))
            .build();
  }

  @Test
  public void testDuplicateResoucesFavorCloserDependencyWithLibraryDep() throws Exception {
    assumeFalse("Android SDK paths don't work on Windows", Platform.detect() == Platform.WINDOWS);

    TargetNode<AndroidBinaryDescriptionArg, AndroidBinaryDescription> binary =
        makeBinaryWithDeps(ImmutableSortedSet.of(mainResTarget, androidLibraryTarget));

    ImmutableList<String> command = getAaptStepShellCommand(binary);

    assertResourcePathOrdering(command, "main_app", "direct_dep", "transitive_dep", "bottom_dep");
  }

  @Test
  public void testDuplicateResoucesFavorCloserDependencyWithTwoLibraryDeps() throws Exception {
    assumeFalse("Android SDK paths don't work on Windows", Platform.detect() == Platform.WINDOWS);

    TargetNode<AndroidBinaryDescriptionArg, AndroidBinaryDescription> binary =
        makeBinaryWithDeps(
            ImmutableSortedSet.of(mainResTarget, androidLibraryTarget, transitiveDepLibTarget));

    ImmutableList<String> command = getAaptStepShellCommand(binary);

    assertResourcePathOrdering(command, "main_app", "direct_dep", "transitive_dep", "bottom_dep");
  }

  @Test
  public void testDuplicateResoucesFavorCloserDependencyWithResourceDep() throws Exception {
    assumeFalse("Android SDK paths don't work on Windows", Platform.detect() == Platform.WINDOWS);

    TargetNode<AndroidBinaryDescriptionArg, AndroidBinaryDescription> binary =
        makeBinaryWithDeps(ImmutableSortedSet.of(mainResTarget, directDepResTarget));

    ImmutableList<String> command = getAaptStepShellCommand(binary);

    assertResourcePathOrdering(command, "main_app", "direct_dep", "transitive_dep", "bottom_dep");
  }

  @Test
  public void testDuplicateResoucesFavorCloserDependencyWithOnlyResourceDep() throws Exception {
    assumeFalse("Android SDK paths don't work on Windows", Platform.detect() == Platform.WINDOWS);

    TargetNode<AndroidBinaryDescriptionArg, AndroidBinaryDescription> binary =
        makeBinaryWithDeps(ImmutableSortedSet.of(directDepResTarget));

    ImmutableList<String> command = getAaptStepShellCommand(binary);

    assertResourcePathOrdering(command, "direct_dep", "transitive_dep", "bottom_dep");
  }

  private void assertResourcePathOrdering(ImmutableList<String> command, String... paths) {
    String errorMessage = String.format("Full command was: %s", Joiner.on(" ").join(command));

    assertThat(
        errorMessage,
        command.stream().filter(s -> "-S".equals(s)).collect(MoreCollectors.toImmutableList()),
        Matchers.hasSize(paths.length));
    int firstResourceFolderArgument = command.indexOf("-S");

    List<Matcher<? super String>> expectedSubslice = new ArrayList<>();
    for (String path : paths) {
      expectedSubslice.add(Matchers.is("-S"));
      expectedSubslice.add(Matchers.is("buck-out/gen/" + path + "/res#resources-symlink-tree/res"));
    }

    assertThat(
        errorMessage,
        command.subList(
            firstResourceFolderArgument, firstResourceFolderArgument + expectedSubslice.size()),
        Matchers.contains(expectedSubslice));
  }

  private TargetNode<AndroidBinaryDescriptionArg, AndroidBinaryDescription> makeBinaryWithDeps(
      ImmutableSortedSet<BuildTarget> deps) {
    return AndroidBinaryBuilder.createBuilder(androidBinaryTarget)
        .setOriginalDeps(deps)
        .setKeystore(keystoreTarget)
        .setManifest(new FakeSourcePath(filesystem, "manifest.xml"))
        .build();
  }

  private ImmutableList<String> getAaptStepShellCommand(
      TargetNode<AndroidBinaryDescriptionArg, AndroidBinaryDescription> binary) {
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            binary,
            mainRes,
            directDepRes,
            transitiveDepRes,
            transitiveDepLib,
            bottomDepRes,
            library,
            keystore);

    ActionGraphAndResolver actionGraphAndResolver =
        ActionGraphCache.getFreshActionGraph(
            BuckEventBusFactory.newInstance(new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1))),
            new DefaultTargetNodeToBuildRuleTransformer(),
            targetGraph);

    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(actionGraphAndResolver.getResolver()));

    ImmutableSet<ImmutableList<Step>> ruleSteps =
        RichStream.from(actionGraphAndResolver.getActionGraph().getNodes())
            .filter(AaptPackageResources.class)
            .filter(
                r ->
                    androidBinaryTarget
                        .getUnflavoredBuildTarget()
                        .equals(r.getBuildTarget().getUnflavoredBuildTarget()))
            .map(
                b ->
                    b.getBuildSteps(
                        FakeBuildContext.withSourcePathResolver(pathResolver),
                        new FakeBuildableContext()))
            .map(
                steps ->
                    steps
                        .stream()
                        .filter(step -> step instanceof AaptStep)
                        .collect(MoreCollectors.toImmutableList()))
            .filter(steps -> !steps.isEmpty())
            .collect(MoreCollectors.toImmutableSet());

    assertEquals(1, ruleSteps.size());
    assertEquals(1, Iterables.getOnlyElement(ruleSteps).size());

    AaptStep step = (AaptStep) Iterables.getOnlyElement(Iterables.getOnlyElement(ruleSteps));

    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(filesystem.getPath("/android-sdk")),
            Optional.of(filesystem.getPath("/android-build-tools")),
            Optional.empty(),
            Optional.empty());

    AndroidPlatformTarget androidPlatformTarget =
        AndroidPlatformTarget.createFromDefaultDirectoryStructure(
            "",
            androidDirectoryResolver,
            "",
            ImmutableSet.of(),
            Optional.empty(),
            Optional.empty());

    ExecutionContext context =
        TestExecutionContext.newBuilder()
            .setAndroidPlatformTargetSupplier(Suppliers.ofInstance(androidPlatformTarget))
            .build();

    return step.getShellCommand(context);
  }
}
