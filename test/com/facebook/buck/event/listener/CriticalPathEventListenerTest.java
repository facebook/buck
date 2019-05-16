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
package com.facebook.buck.event.listener;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.event.listener.CriticalPathEventListener.CriticalPathNode;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CriticalPathEventListenerTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private CriticalPathEventListener listener;

  @Before
  public void setUp() throws Exception {
    listener = new CriticalPathEventListener(tmp.getRoot());
  }

  /**
   * Test for finding critical path in the following linear action graph:
   *
   * <pre>
   *
   *   a (exec: 1)
   *   |
   *   b (exec: 8)
   *   |
   *   c (exec: 2)
   *
   * </pre>
   */
  @SuppressWarnings("unused")
  @Test
  public void linearCriticalPath() {
    BuildRule c = execRule("c", 2);
    BuildRule b = execRule("b", 8, c);
    BuildRule a = execRule("a", 1, b);

    Collection<Pair<BuildTarget, CriticalPathNode>> criticalPath = listener.getCriticalPath();
    assertThat(criticalPath, Matchers.hasSize(3));

    Iterator<Pair<BuildTarget, CriticalPathNode>> iterator = criticalPath.iterator();
    assertCriticalPathPair(iterator.next(), "c", 2, 2);
    assertCriticalPathPair(iterator.next(), "b", 8, 10, "c");
    assertCriticalPathPair(iterator.next(), "a", 1, 11, "b");
  }

  /**
   * Test for finding critical path in the following action graph:
   *
   * <pre>
   *                          a(cache)
   *                          |
   *   b(cache)   c(exec: 1)  d(exec: 4)
   *    \          \        /
   *    e(cache)    f(cache)
   *          \    /
   *          g(cache)
   *         /        \
   *      i(exec: 3)  h(cache)
   *    /         \
   *   j(cache) -> k(cache)
   *               |
   *               l(cache)
   *               |
   *               m(cache)
   * </pre>
   */
  @SuppressWarnings("unused")
  @Test
  public void notLinearCriticalPath() {
    BuildRule m = cachedRule("m");
    BuildRule l = cachedRule("l", m);
    BuildRule k = cachedRule("k", l);
    BuildRule j = cachedRule("j", k);
    BuildRule i = execRule("i", 3, j, k);
    BuildRule h = cachedRule("h");
    BuildRule g = cachedRule("g", i, h);
    BuildRule e = cachedRule("e", g);
    BuildRule f = cachedRule("f", g);
    BuildRule b = cachedRule("b", e);
    BuildRule c = execRule("c", 1, f);
    BuildRule d = execRule("d", 4, f);
    BuildRule a = cachedRule("a", d);

    Collection<Pair<BuildTarget, CriticalPathNode>> criticalPath = listener.getCriticalPath();
    assertThat(criticalPath, Matchers.hasSize(4));

    Iterator<Pair<BuildTarget, CriticalPathNode>> iterator = criticalPath.iterator();
    assertCriticalPathPair(iterator.next(), "i", 3, 3);
    assertCriticalPathPair(iterator.next(), "g", 0, 3, "i");
    assertCriticalPathPair(iterator.next(), "f", 0, 3, "g");
    assertCriticalPathPair(iterator.next(), "d", 4, 7, "f");
  }

  private BuildRule cachedRule(String buildTargetName, BuildRule... buildRules) {
    return execRule(buildTargetName, 0, buildRules);
  }

  private BuildRule execRule(String buildTargetName, long execTime, BuildRule... buildRules) {
    BuildRule buildRule =
        new FakeBuildRule(
            BuildTargetFactory.newInstance("//:" + buildTargetName),
            ImmutableSortedSet.copyOf(buildRules));
    listener.handleBuildRule(buildRule, execTime);
    return buildRule;
  }

  private void assertCriticalPathPair(
      Pair<BuildTarget, CriticalPathNode> criticalPathPair,
      String nodeName,
      long expectedElapsedTime,
      long expectedTotalTime) {
    assertCriticalPathPair(
        criticalPathPair, nodeName, expectedElapsedTime, expectedTotalTime, null);
  }

  private void assertCriticalPathPair(
      Pair<BuildTarget, CriticalPathNode> criticalPathPair,
      String nodeName,
      long expectedElapsedTime,
      long expectedTotalTime,
      String prevNodeName) {
    assertBuildTarget(criticalPathPair.getFirst(), nodeName);

    CriticalPathNode criticalPathNode = criticalPathPair.getSecond();
    assertThat(criticalPathNode.getElapsedTime(), equalTo(expectedElapsedTime));
    assertThat(criticalPathNode.getTotalElapsedTime(), equalTo(expectedTotalTime));
    if (prevNodeName == null) {
      assertThat(criticalPathNode.getPreviousNode(), nullValue());
    } else {
      assertBuildTarget(Objects.requireNonNull(criticalPathNode.getPreviousNode()), prevNodeName);
    }
  }

  private void assertBuildTarget(BuildTarget buildTarget, String expectedName) {
    assertThat(buildTarget.getFullyQualifiedName(), equalTo("//:" + expectedName));
  }
}
