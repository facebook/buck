/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.versions;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.hamcrest.Matchers;
import org.junit.Test;

public class VersionedTargetGraphTest {

  @Test
  public void getNodeWithExtraFlavors() {
    TargetNode<?, ?> node = createTargetNode("bar");
    TargetGraph graph = VersionedTargetGraphFactory.newInstance(ImmutableList.of(node));
    TargetNode<?, ?> result =
        graph.get(node.getBuildTarget().withAppendedFlavors(InternalFlavor.of("hello")));
    assertThat(result, Matchers.notNullValue());
    assertThat(
        result.getBuildTarget().getFlavors(),
        Matchers.containsInAnyOrder(InternalFlavor.of("hello")));
    assertNodeCreatedFrom(result, node);
  }

  @Test
  public void getNodeWithExtraFlavorsOnFlavoredNode() {
    TargetNode<?, ?> node = createTargetNode("bar#hello");
    TargetGraph graph = VersionedTargetGraphFactory.newInstance(ImmutableList.of(node));
    TargetNode<?, ?> result =
        graph.get(node.getBuildTarget().withAppendedFlavors(InternalFlavor.of("world")));
    assertThat(result, Matchers.notNullValue());
    assertThat(
        result.getBuildTarget().getFlavors(),
        Matchers.containsInAnyOrder(InternalFlavor.of("hello"), InternalFlavor.of("world")));
    assertNodeCreatedFrom(result, node);
  }

  @Test
  public void getNodeWithExtraFlavorsWithMultipleCandidatesWithSubsetRelation() {
    TargetNode<?, ?> node1 = createTargetNode("bar#hello");
    TargetNode<?, ?> node2 = createTargetNode("bar#hello,bye");
    TargetGraph graph = VersionedTargetGraphFactory.newInstance(ImmutableList.of(node1, node2));
    TargetNode<?, ?> result =
        graph.get(node2.getBuildTarget().withAppendedFlavors(InternalFlavor.of("world")));
    assertThat(result, Matchers.notNullValue());
    assertThat(
        result.getBuildTarget().getFlavors(),
        Matchers.containsInAnyOrder(
            InternalFlavor.of("hello"), InternalFlavor.of("bye"), InternalFlavor.of("world")));
    assertNodeCreatedFrom(result, node2);
  }

  @Test(expected = IllegalStateException.class)
  public void getNodeWithExtraFlavorsWithMultipleAmbiguousCandidates() {
    TargetNode<?, ?> node1 = createTargetNode("bar#one,two");
    TargetNode<?, ?> node2 = createTargetNode("bar#two,three");
    TargetGraph graph = VersionedTargetGraphFactory.newInstance(ImmutableList.of(node1, node2));
    graph.get(
        node2
            .getBuildTarget()
            .withFlavors(
                InternalFlavor.of("one"), InternalFlavor.of("two"), InternalFlavor.of("three")));
  }

  @Test
  public void getNodeWithNonVersionExtraFlavorsFallsBackToUnversioned() {
    TargetNode<?, ?> node = createTargetNode("bar#hello");
    TargetNode<?, ?> versionedNode = createTargetNode("bar#version");
    TargetGraph graph =
        VersionedTargetGraphFactory.newInstance(
            ImmutableMap.of(
                node.getBuildTarget().withFlavors(), node,
                versionedNode.getBuildTarget(), versionedNode));
    TargetNode<?, ?> result =
        graph.get(node.getBuildTarget().withFlavors(InternalFlavor.of("world")));
    assertThat(result, Matchers.notNullValue());
    assertThat(
        result.getBuildTarget().getFlavors(),
        Matchers.containsInAnyOrder(InternalFlavor.of("world")));
    assertNodeCreatedFrom(result, node);
  }

  private void assertNodeCreatedFrom(TargetNode<?, ?> node, TargetNode<?, ?> parent) {
    assertThat(
        node.getSelectedVersions().orElseThrow(RuntimeException::new).keySet(),
        Matchers.contains(parent.getBuildTarget()));
  }

  private TargetNode<?, ?> createTargetNode(String name, TargetNode<?, ?>... deps) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:" + name);
    JavaLibraryBuilder targetNodeBuilder = JavaLibraryBuilder.createBuilder(buildTarget);
    // Use the selected versions field to embed the original build target name.  We'll use this to
    // verify the correct node was recovered from the target graph.
    targetNodeBuilder.setSelectedVersions(ImmutableMap.of(buildTarget, Version.of("1.0")));
    for (TargetNode<?, ?> dep : deps) {
      targetNodeBuilder.addDep(dep.getBuildTarget());
    }
    return targetNodeBuilder.build();
  }
}
