/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBundleBuilder;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.apple.XcodeWorkspaceConfigBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Test;

public class ProjectCommandXcodeTest {

  private TargetNode<?> barLibNode;
  private TargetNode<?> fooLibNode;
  private TargetNode<?> fooBinBinaryNode;
  private TargetNode<?> fooBinNode;
  private TargetNode<?> bazLibNode;
  private TargetNode<?> bazTestNode;
  private TargetNode<?> fooTestNode;
  private TargetNode<?> fooBinTestNode;
  private TargetNode<?> quxBinNode;
  private TargetNode<?> workspaceNode;
  private TargetNode<?> smallWorkspaceNode;

  TargetGraph targetGraph;

  @Before
  public void buildGraph() {
    // Create the following dep tree:
    //
    // FooBin -has-test-> FooBinTest
    // |
    // V
    // FooLib -has-test-> FooLibTest
    // |                  |
    // V                  V
    // BarLib             BazLib -has-test-> BazLibTest
    // ^
    // |
    // QuxBin
    //
    // FooBin and BazLib use "tests" to specify their tests while FooLibTest uses source_under_test
    // to specify that it is a test of FooLib.

    BuildTarget bazTestTarget = BuildTarget.builder("//baz", "xctest").build();
    BuildTarget fooBinTestTarget = BuildTarget.builder("//foo", "bin-xctest").build();

    BuildTarget barLibTarget = BuildTarget.builder("//bar", "lib").build();
    barLibNode = AppleLibraryBuilder
        .createBuilder(barLibTarget)
        .build();

    BuildTarget bazLibTarget = BuildTarget.builder("//baz", "lib").build();
    bazLibNode = AppleLibraryBuilder
        .createBuilder(bazLibTarget)
        .setTests(Optional.of(ImmutableSortedSet.of(bazTestTarget)))
        .build();

    BuildTarget fooTestTarget = BuildTarget.builder("//foo", "lib-xctest").build();
    fooTestNode = AppleTestBuilder
        .createBuilder(fooTestTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .build();

    BuildTarget fooLibTarget = BuildTarget.builder("//foo", "lib").build();
    fooLibNode = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .setTests(Optional.of(ImmutableSortedSet.of(fooTestTarget)))
        .build();

    BuildTarget fooBinBinaryTarget = BuildTarget.builder("//foo", "binbinary").build();
    fooBinBinaryNode = AppleBinaryBuilder
        .createBuilder(fooBinBinaryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooLibTarget)))
        .build();

    BuildTarget fooBinTarget = BuildTarget.builder("//foo", "bin").build();
    fooBinNode = AppleBundleBuilder
        .createBuilder(fooBinTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.APP))
        .setBinary(fooBinBinaryTarget)
        .setTests(Optional.of(ImmutableSortedSet.of(fooBinTestTarget)))
        .build();

    bazTestNode = AppleTestBuilder
        .createBuilder(bazTestTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(bazLibTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .build();

    fooBinTestNode = AppleTestBuilder
        .createBuilder(fooBinTestTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooBinTarget)))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .build();

    BuildTarget quxBinTarget = BuildTarget.builder("//qux", "bin").build();
    quxBinNode = AppleBinaryBuilder
        .createBuilder(quxBinTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(barLibTarget)))
        .build();

    BuildTarget workspaceTarget = BuildTarget.builder("//foo", "workspace").build();
    workspaceNode = XcodeWorkspaceConfigBuilder
        .createBuilder(workspaceTarget)
        .setWorkspaceName(Optional.of("workspace"))
        .setSrcTarget(Optional.of(fooBinTarget))
        .build();

    BuildTarget smallWorkspaceTarget = BuildTarget.builder("//baz", "small-workspace").build();
    smallWorkspaceNode = XcodeWorkspaceConfigBuilder
        .createBuilder(smallWorkspaceTarget)
        .setWorkspaceName(Optional.of("small-workspace"))
        .setSrcTarget(Optional.of(bazLibTarget))
        .build();

    targetGraph = TargetGraphFactory.newInstance(
        barLibNode,
        fooLibNode,
        fooBinBinaryNode,
        fooBinNode,
        bazLibNode,
        bazTestNode,
        fooTestNode,
        fooBinTestNode,
        quxBinNode,
        workspaceNode,
        smallWorkspaceNode);
  }

  @Test
  public void testCreateTargetGraphWithoutTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommandOptions.Ide.XCODE,
        ImmutableSet.<BuildTarget>of(),
        /* withTests = */ false);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?>>of(
            workspaceNode,
            fooBinNode,
            fooBinBinaryNode,
            fooLibNode,
            barLibNode,
            smallWorkspaceNode,
            bazLibNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphWithTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommandOptions.Ide.XCODE,
        ImmutableSet.<BuildTarget>of(),
        /* withTests = */ true);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?>>of(
            workspaceNode,
            fooBinNode,
            fooBinBinaryNode,
            fooLibNode,
            fooBinTestNode,
            fooTestNode,
            barLibNode,
            smallWorkspaceNode,
            bazLibNode,
            bazTestNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSliceWithoutTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommandOptions.Ide.XCODE,
        ImmutableSet.of(workspaceNode.getBuildTarget()),
        /* withTests = */ false);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?>>of(
            workspaceNode,
            fooBinNode,
            fooBinBinaryNode,
            fooLibNode,
            barLibNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSliceWithTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommandOptions.Ide.XCODE,
        ImmutableSet.of(workspaceNode.getBuildTarget()),
        /* withTests = */ true);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?>>of(
            workspaceNode,
            fooBinNode,
            fooBinBinaryNode,
            fooLibNode,
            fooBinTestNode,
            fooTestNode,
            barLibNode,
            bazLibNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSmallSliceWithoutTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommandOptions.Ide.XCODE,
        ImmutableSet.of(smallWorkspaceNode.getBuildTarget()),
        /* withTests = */ false);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?>>of(
            smallWorkspaceNode,
            bazLibNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSmallSliceWithTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommandOptions.Ide.XCODE,
        ImmutableSet.of(smallWorkspaceNode.getBuildTarget()),
        /* withTests = */ true);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?>>of(
            smallWorkspaceNode,
            bazLibNode,
            bazTestNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }
}
