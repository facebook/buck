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

import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaTestBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ProjectConfigBuilder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Test;

public class ProjectCommandIntellijTest {

  private TargetNode<?, ?> dummyRootBinNode;
  private TargetNode<?, ?> barLibNode;
  private TargetNode<?, ?> fooLibNode;
  private TargetNode<?, ?> fooBinNode;
  private TargetNode<?, ?> bazLibNode;
  private TargetNode<?, ?> bazTestNode;
  private TargetNode<?, ?> fooTestNode;
  private TargetNode<?, ?> fooBinTestNode;
  private TargetNode<?, ?> quxBinNode;
  private TargetNode<?, ?> fooProjectNode;
  private TargetNode<?, ?> bazProjectNode;
  private TargetNode<?, ?> dummyProjectNode;

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

    BuildTarget dummyRootBinTarget = BuildTargetFactory.newInstance("//:bindummy");
    BuildTarget barLibTarget = BuildTargetFactory.newInstance("//bar:lib");
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    BuildTarget fooBinTarget = BuildTargetFactory.newInstance("//foo:binbinary");
    BuildTarget bazLibTarget = BuildTargetFactory.newInstance("//baz:lib");
    BuildTarget bazTestTarget = BuildTargetFactory.newInstance("//baz:xctest");
    BuildTarget fooTestTarget = BuildTargetFactory.newInstance("//foo:lib-xctest");
    BuildTarget fooBinTestTarget = BuildTargetFactory.newInstance("//foo:bin-xctest");
    BuildTarget quxBinTarget = BuildTargetFactory.newInstance("//qux:bin");
    BuildTarget fooProjectTarget = BuildTargetFactory.newInstance("//foo:foo");
    BuildTarget bazProjectTarget = BuildTargetFactory.newInstance("//baz:baz");
    BuildTarget dummyProjectTarget = BuildTargetFactory.newInstance("//:dummy");

    dummyRootBinNode = new JavaBinaryRuleBuilder(dummyRootBinTarget)
        .build();
    barLibNode = JavaLibraryBuilder
        .createBuilder(barLibTarget)
        .build();
    fooLibNode = JavaLibraryBuilder
        .createBuilder(fooLibTarget)
        .addDep(barLibTarget)
        .addTest(fooTestTarget)
        .build();
    fooBinNode = new JavaBinaryRuleBuilder(fooBinTarget)
        .setDeps(ImmutableSortedSet.of(fooLibTarget))
        .addTest(fooBinTestTarget)
        .build();
    bazLibNode = JavaLibraryBuilder
        .createBuilder(bazLibTarget)
        .addTest(bazTestTarget)
        .build();
    bazTestNode = JavaTestBuilder
        .createBuilder(bazTestTarget)
        .build();
    fooTestNode = JavaTestBuilder
        .createBuilder(fooTestTarget)
        .addDep(bazLibTarget)
        .build();
    fooBinTestNode = JavaTestBuilder
        .createBuilder(fooBinTestTarget)
        .build();
    quxBinNode = new JavaBinaryRuleBuilder(quxBinTarget)
        .setDeps(ImmutableSortedSet.of(barLibTarget))
        .build();
    fooProjectNode = ProjectConfigBuilder
        .createBuilder(fooProjectTarget)
        .setSrcRule(fooBinTarget)
        .build();
    bazProjectNode = ProjectConfigBuilder
        .createBuilder(bazProjectTarget)
        .setSrcRule(bazLibTarget)
        .build();
    dummyProjectNode = ProjectConfigBuilder
        .createBuilder(dummyProjectTarget)
        .setSrcRule(dummyRootBinTarget)
        .build();

    targetGraph = TargetGraphFactory.newInstance(
        dummyRootBinNode,
        barLibNode,
        fooLibNode,
        fooBinNode,
        bazLibNode,
        bazTestNode,
        fooTestNode,
        fooBinTestNode,
        quxBinNode,
        fooProjectNode,
        bazProjectNode,
        dummyProjectNode);
  }

  @Test
  public void testCreateTargetGraphWithoutTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.INTELLIJ,
        ImmutableSet.of(),
        /* withTests = */ false,
        /* withDependenciesTests = */ false);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?, ?>>of(
            dummyRootBinNode,
            dummyProjectNode,
            fooProjectNode,
            fooBinNode,
            fooLibNode,
            barLibNode,
            bazProjectNode,
            bazLibNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphWithTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.INTELLIJ,
        ImmutableSet.of(),
        /* withTests = */ true,
        /* withDependenciesTests = */ true);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?, ?>>of(
            dummyRootBinNode,
            dummyProjectNode,
            fooProjectNode,
            fooBinNode,
            fooLibNode,
            fooBinTestNode,
            fooTestNode,
            barLibNode,
            bazProjectNode,
            bazLibNode,
            bazTestNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSliceWithoutTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.INTELLIJ,
        ImmutableSet.of(fooProjectNode.getBuildTarget()),
        /* withTests = */ false,
        /* withDependenciesTests = */ false);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?, ?>>of(
            dummyRootBinNode,
            dummyProjectNode,
            fooProjectNode,
            fooBinNode,
            fooLibNode,
            barLibNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSliceWithTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.INTELLIJ,
        ImmutableSet.of(fooProjectNode.getBuildTarget()),
        /* withTests = */ true,
        /* withDependenciesTests = */ true);

    assertEquals(
        ImmutableSortedSet.<TargetNode<?, ?>>of(
            dummyRootBinNode,
            dummyProjectNode,
            fooProjectNode,
            fooBinNode,
            fooLibNode,
            fooBinTestNode,
            fooTestNode,
            barLibNode,
            bazProjectNode,
            bazLibNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSmallSliceWithoutTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.INTELLIJ,
        ImmutableSet.of(bazProjectNode.getBuildTarget()),
        /* withTests = */ false,
        /* withDependenciesTests = */ false);

    assertEquals(
        ImmutableSortedSet.of(
            dummyRootBinNode,
            dummyProjectNode,
            bazProjectNode,
            bazLibNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }

  @Test
  public void testCreateTargetGraphForSmallSliceWithTests() {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.INTELLIJ,
        ImmutableSet.of(bazProjectNode.getBuildTarget()),
        /* withTests = */ true,
        /* withDependenciesTests = */ true);

    assertEquals(
        ImmutableSortedSet.of(
            dummyRootBinNode,
            dummyProjectNode,
            bazProjectNode,
            bazLibNode,
            bazTestNode),
        ImmutableSortedSet.copyOf(
            targetGraphAndTargets.getTargetGraph().getNodes()));
  }
}
