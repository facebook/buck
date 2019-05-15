/*
 * Copyright 2019-present Facebook, Inc.
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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.ActionGraphSerializer.ActionGraphNode;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public class ActionGraphSerializerTest {

  private ActionGraphBuilder buildRuleResolver = new TestActionGraphBuilder();

  /**
   * Test for finding critical path in the following action graph:
   *
   * <pre>
   *                       a
   *                      /|\
   *                     / | \(runtime deps)
   *                    /  |  r2
   *                   /   |
   *                  /    | (runtime deps)
   *         b   c   d     r1
   *         \   \  /
   *          e   f
   *           \ /
   *            g
   *          /  \
   *         i    h
   *        / \
   *       j-->k
   *           |
   *           l
   *           |
   *           m
   * </pre>
   */
  @Test
  public void serializeActionGraph() {
    BuildRule m = createRule("m");
    BuildRule l = createRule("l", m);
    BuildRule k = createRule("k", l);
    BuildRule j = createRule("j", k);
    BuildRule i = createRule("i", j, k);
    BuildRule h = createRule("h");
    BuildRule g = createRule("g", i, h);
    BuildRule e = createRule("e", g);
    BuildRule f = createRule("f", g);
    BuildRule b = createRule("b", e);
    BuildRule c = createRule("c", f);
    BuildRule d = createRule("d", f);
    BuildRule r1 = createRule("r1");
    BuildRule r2 = createRule("r2");
    BuildRule a = createRuleWithRuntimeDeps("a", ImmutableSortedSet.of(d), r1, r2);

    ActionGraphSerializer actionGraphSerializer =
        new ActionGraphSerializer(
            buildRuleResolver,
            ImmutableSet.of(a.getBuildTarget(), b.getBuildTarget(), c.getBuildTarget()),
            null);

    actionGraphSerializer.serialize(
        actionGraphNodes -> {
          Iterator<ActionGraphNode> iterator = actionGraphNodes.iterator();
          assertLeaves(Arrays.asList(iterator.next(), iterator.next()), "h", "m");
          assertActionGraphNode(iterator.next(), "l", "m");
          assertActionGraphNode(iterator.next(), "k", "l");
          assertActionGraphNode(iterator.next(), "j", "k");
          assertActionGraphNode(iterator.next(), "i", "k", "j");
          assertActionGraphNode(iterator.next(), "g", "h", "i");
          assertActionGraphNode(iterator.next(), "f", "g");
          assertActionGraphNode(iterator.next(), "d", "f");
          assertLeaves(Arrays.asList(iterator.next(), iterator.next()), "r1", "r2");
          assertActionGraphNode(iterator.next(), "a", Arrays.asList("d"), "r1", "r2");
          assertActionGraphNode(iterator.next(), "e", "g");
          assertActionGraphNode(iterator.next(), "b", "e");
          assertActionGraphNode(iterator.next(), "c", "f");
          assertThat(iterator.hasNext(), is(false));
        });
  }

  private BuildRule createRule(String buildTargetName, BuildRule... deps) {
    FakeBuildRule buildRule =
        new FakeBuildRule(
            BuildTargetFactory.newInstance("//:" + buildTargetName),
            ImmutableSortedSet.copyOf(deps));
    buildRuleResolver.addToIndex(buildRule);
    return buildRule;
  }

  private BuildRule createRuleWithRuntimeDeps(
      String buildTargetName, ImmutableSortedSet<BuildRule> deps, BuildRule... runtimeDeps) {
    FakeBuildRule buildRule =
        new FakeBuildRule(BuildTargetFactory.newInstance("//:" + buildTargetName), deps);
    buildRule.setRuntimeDeps(runtimeDeps);
    buildRule.updateBuildRuleResolver(buildRuleResolver);
    buildRuleResolver.addToIndex(buildRule);
    return buildRule;
  }

  private void assertLeaves(Collection<ActionGraphNode> leaves, String... expectedNames) {
    assertThat(leaves.size(), equalTo(expectedNames.length));

    List<String> extractedNames = new ArrayList<>();
    for (ActionGraphNode leaf : leaves) {
      assertThat(leaf.getBuildDeps(), is(empty()));
      assertThat(leaf.getRuntimeDeps(), is(empty()));

      BuildTarget buildTarget = leaf.getBuildTarget();
      String name = buildTarget.getFullyQualifiedName();
      extractedNames.add(name.replace("//:", ""));
    }

    assertThat(extractedNames, containsInAnyOrder(expectedNames));
  }

  private void assertActionGraphNode(ActionGraphNode node, String expectedName, String... deps) {
    assertActionGraphNode(node, expectedName, Arrays.asList(deps));
  }

  private void assertActionGraphNode(
      ActionGraphNode node, String expectedName, List<String> deps, String... runtimeDeps) {
    assertThat(node.getBuildTarget().getFullyQualifiedName(), equalTo("//:" + expectedName));

    if (runtimeDeps.length == 0) {
      assertThat(node.getRuntimeDeps(), is(empty()));
    } else {
      assertDeps(node.getRuntimeDeps(), runtimeDeps);
    }

    if (deps.isEmpty()) {
      assertThat(node.getBuildDeps(), is(empty()));
    } else {
      assertDeps(node.getBuildDeps(), deps.toArray(new String[deps.size()]));
    }
  }

  private void assertDeps(ImmutableSet<BuildRule> actual, String... expected) {
    assertThat(
        actual.stream()
            .map(BuildRule::getBuildTarget)
            .map(BuildTarget::getFullyQualifiedName)
            .map(s -> s.replace("//:", ""))
            .collect(Collectors.toList()),
        containsInAnyOrder(expected));
  }
}
