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

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProviderBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.jvm.java.KeystoreDescriptionArg;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.IncrementingFakeClock;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DuplicateResourcesTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private BuildTarget mainResTarget;
  private BuildTarget directDepResTarget;
  private BuildTarget transitiveDepResTarget;
  private BuildTarget transitiveDepLibTarget;
  private BuildTarget bottomDepResTarget;
  private BuildTarget androidLibraryTarget;
  private BuildTarget androidBinaryTarget;
  private BuildTarget keystoreTarget;

  private FakeProjectFilesystem filesystem;

  private TargetNode<AndroidResourceDescriptionArg> mainRes;
  private TargetNode<AndroidResourceDescriptionArg> directDepRes;
  private TargetNode<AndroidResourceDescriptionArg> transitiveDepRes;
  private TargetNode<AndroidResourceDescriptionArg> bottomDepRes;
  private TargetNode<AndroidLibraryDescriptionArg> transitiveDepLib;
  private TargetNode<AndroidLibraryDescriptionArg> library;
  private TargetNode<KeystoreDescriptionArg> keystore;

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
  public void makeRules() {
    filesystem = new FakeProjectFilesystem(tmp.getRoot());

    mainResTarget = BuildTargetFactory.newInstance(filesystem, "//main_app:res");
    directDepResTarget = BuildTargetFactory.newInstance(filesystem, "//direct_dep:res");
    transitiveDepResTarget = BuildTargetFactory.newInstance(filesystem, "//transitive_dep:res");
    transitiveDepLibTarget = BuildTargetFactory.newInstance(filesystem, "//transitive_dep:library");
    bottomDepResTarget = BuildTargetFactory.newInstance(filesystem, "//bottom_dep:res");
    androidLibraryTarget = BuildTargetFactory.newInstance(filesystem, "//direct_dep:library");
    androidBinaryTarget = BuildTargetFactory.newInstance(filesystem, "//main_app:binary");
    keystoreTarget = BuildTargetFactory.newInstance(filesystem, "//main_app:keystore");

    mainRes =
        AndroidResourceBuilder.createBuilder(mainResTarget)
            .setRes(FakeSourcePath.of(filesystem, "main_app/res"))
            .setRDotJavaPackage("package")
            .build();

    directDepRes =
        AndroidResourceBuilder.createBuilder(directDepResTarget)
            .setRes(FakeSourcePath.of(filesystem, "direct_dep/res"))
            .setRDotJavaPackage("package")
            .setDeps(ImmutableSortedSet.of(transitiveDepResTarget, transitiveDepLibTarget))
            .build();

    transitiveDepLib =
        AndroidLibraryBuilder.createBuilder(transitiveDepLibTarget)
            .addDep(transitiveDepResTarget)
            .build();

    transitiveDepRes =
        AndroidResourceBuilder.createBuilder(transitiveDepResTarget)
            .setRes(FakeSourcePath.of(filesystem, "transitive_dep/res"))
            .setRDotJavaPackage("package")
            .setDeps(ImmutableSortedSet.of(bottomDepResTarget))
            .build();

    bottomDepRes =
        AndroidResourceBuilder.createBuilder(bottomDepResTarget)
            .setRes(FakeSourcePath.of(filesystem, "bottom_dep/res"))
            .setRDotJavaPackage("package")
            .build();

    library =
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addDep(directDepResTarget)
            .addDep(transitiveDepLibTarget)
            .build();

    keystore =
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of(filesystem, "store"))
            .setProperties(FakeSourcePath.of(filesystem, "properties"))
            .build();
  }

  @Test
  public void testDuplicateResoucesFavorCloserDependencyWithLibraryDep() {
    assumeFalse("Android SDK paths don't work on Windows", Platform.detect() == Platform.WINDOWS);

    TargetNode<AndroidBinaryDescriptionArg> binary =
        makeBinaryWithDeps(ImmutableSortedSet.of(mainResTarget, androidLibraryTarget));

    ImmutableList<String> command = getAaptStepShellCommand(binary);

    assertResourcePathOrdering(command, "main_app", "direct_dep", "transitive_dep", "bottom_dep");
  }

  @Test
  public void testDuplicateResoucesFavorCloserDependencyWithTwoLibraryDeps() {
    assumeFalse("Android SDK paths don't work on Windows", Platform.detect() == Platform.WINDOWS);

    TargetNode<AndroidBinaryDescriptionArg> binary =
        makeBinaryWithDeps(
            ImmutableSortedSet.of(mainResTarget, androidLibraryTarget, transitiveDepLibTarget));

    ImmutableList<String> command = getAaptStepShellCommand(binary);

    assertResourcePathOrdering(command, "main_app", "direct_dep", "transitive_dep", "bottom_dep");
  }

  @Test
  public void testDuplicateResoucesFavorCloserDependencyWithResourceDep() {
    assumeFalse("Android SDK paths don't work on Windows", Platform.detect() == Platform.WINDOWS);

    TargetNode<AndroidBinaryDescriptionArg> binary =
        makeBinaryWithDeps(ImmutableSortedSet.of(mainResTarget, directDepResTarget));

    ImmutableList<String> command = getAaptStepShellCommand(binary);

    assertResourcePathOrdering(command, "main_app", "direct_dep", "transitive_dep", "bottom_dep");
  }

  @Test
  public void testDuplicateResoucesFavorCloserDependencyWithOnlyResourceDep() {
    assumeFalse("Android SDK paths don't work on Windows", Platform.detect() == Platform.WINDOWS);

    TargetNode<AndroidBinaryDescriptionArg> binary =
        makeBinaryWithDeps(ImmutableSortedSet.of(directDepResTarget));

    ImmutableList<String> command = getAaptStepShellCommand(binary);

    assertResourcePathOrdering(command, "direct_dep", "transitive_dep", "bottom_dep");
  }

  private void assertResourcePathOrdering(ImmutableList<String> command, String... paths) {
    String errorMessage = String.format("Full command was: %s", Joiner.on(" ").join(command));

    assertThat(
        errorMessage,
        command.stream().filter(s -> "-S".equals(s)).collect(ImmutableList.toImmutableList()),
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

  private TargetNode<AndroidBinaryDescriptionArg> makeBinaryWithDeps(
      ImmutableSortedSet<BuildTarget> deps) {
    return AndroidBinaryBuilder.createBuilder(androidBinaryTarget)
        .setOriginalDeps(deps)
        .setKeystore(keystoreTarget)
        .setManifest(FakeSourcePath.of(filesystem, "manifest.xml"))
        .build();
  }

  private ImmutableList<String> getAaptStepShellCommand(
      TargetNode<AndroidBinaryDescriptionArg> binary) {
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

    ActionGraphAndBuilder actionGraphAndBuilder =
        new ActionGraphProviderBuilder()
            .withEventBus(
                BuckEventBusForTests.newInstance(
                    new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1))))
            .withCellProvider(
                new TestCellBuilder()
                    .setToolchainProvider(
                        AndroidBinaryBuilder.createToolchainProviderForAndroidBinary())
                    .setFilesystem(filesystem)
                    .build()
                    .getCellProvider())
            .build()
            .getFreshActionGraph(new DefaultTargetNodeToBuildRuleTransformer(), targetGraph);

    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(
            new SourcePathRuleFinder(actionGraphAndBuilder.getActionGraphBuilder()));

    ImmutableSet<ImmutableList<Step>> ruleSteps =
        RichStream.from(actionGraphAndBuilder.getActionGraph().getNodes())
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
                        .collect(ImmutableList.toImmutableList()))
            .filter(steps -> !steps.isEmpty())
            .collect(ImmutableSet.toImmutableSet());

    assertEquals(1, ruleSteps.size());
    assertEquals(1, Iterables.getOnlyElement(ruleSteps).size());

    AaptStep step = (AaptStep) Iterables.getOnlyElement(Iterables.getOnlyElement(ruleSteps));

    ExecutionContext context = TestExecutionContext.newBuilder().build();

    return step.getShellCommand(context);
  }
}
