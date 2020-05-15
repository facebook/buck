/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.support.criticalpath;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Assert;
import org.junit.Test;

public class CriticalPathBuilderTest {
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
  @Test
  public void linearPath() {
    CriticalPathBuilder builder = new CriticalPathBuilder();
    BuildRule c = execRule(builder, "c", 2);
    BuildRule b = execRule(builder, "b", 8, c);
    BuildRule a = execRule(builder, "a", 1, b);
    ImmutableList<ReportableCriticalPathNode> criticalPath = builder.getCriticalPath();
    Assert.assertEquals(c.getBuildTarget(), criticalPath.get(0).getTarget());
    Assert.assertEquals(2, criticalPath.get(0).getPathCostMilliseconds());
    Assert.assertEquals(2, criticalPath.get(0).getExecutionTimeMilliseconds());
    Assert.assertEquals(b.getBuildTarget(), criticalPath.get(1).getTarget());
    Assert.assertEquals(10, criticalPath.get(1).getPathCostMilliseconds());
    Assert.assertEquals(8, criticalPath.get(1).getExecutionTimeMilliseconds());
    Assert.assertEquals(a.getBuildTarget(), criticalPath.get(2).getTarget());
    Assert.assertEquals(11, criticalPath.get(2).getPathCostMilliseconds());
    Assert.assertEquals(1, criticalPath.get(2).getExecutionTimeMilliseconds());
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
  @Test
  @SuppressWarnings("unused")
  public void notLinearCriticalPath() {
    CriticalPathBuilder builder = new CriticalPathBuilder();
    BuildRule m = cachedRule(builder, "m");
    BuildRule l = cachedRule(builder, "l", m);
    BuildRule k = cachedRule(builder, "k", l);
    BuildRule j = cachedRule(builder, "j", k);
    BuildRule i = execRule(builder, "i", 3, j, k);
    BuildRule h = cachedRule(builder, "h");
    BuildRule g = cachedRule(builder, "g", i, h);
    BuildRule e = cachedRule(builder, "e", g);
    BuildRule f = cachedRule(builder, "f", g);
    BuildRule b = cachedRule(builder, "b", e);
    BuildRule c = execRule(builder, "c", 1, f);
    BuildRule d = execRule(builder, "d", 4, f);
    BuildRule a = cachedRule(builder, "a", d);

    ImmutableList<ReportableCriticalPathNode> criticalPath = builder.getCriticalPath();
    Assert.assertEquals(4, criticalPath.size());

    Assert.assertEquals(i.getBuildTarget(), criticalPath.get(0).getTarget());
    Assert.assertEquals(3, criticalPath.get(0).getExecutionTimeMilliseconds());
    Assert.assertEquals(3, criticalPath.get(0).getPathCostMilliseconds());

    Assert.assertEquals(g.getBuildTarget(), criticalPath.get(1).getTarget());
    Assert.assertEquals(0, criticalPath.get(1).getExecutionTimeMilliseconds());
    Assert.assertEquals(3, criticalPath.get(1).getPathCostMilliseconds());

    Assert.assertEquals(f.getBuildTarget(), criticalPath.get(2).getTarget());
    Assert.assertEquals(0, criticalPath.get(2).getExecutionTimeMilliseconds());
    Assert.assertEquals(3, criticalPath.get(2).getPathCostMilliseconds());

    Assert.assertEquals(d.getBuildTarget(), criticalPath.get(3).getTarget());
    Assert.assertEquals(4, criticalPath.get(3).getExecutionTimeMilliseconds());
    Assert.assertEquals(7, criticalPath.get(3).getPathCostMilliseconds());
  }

  /**
   * Test for finding the critical path in the following action graph:
   *
   * <pre>
   *
   *            a (exec: 4) --\
   *          /     |          \
   *   b (exec: 5)  c (exec: 9) d (exec: 4)
   *
   * </pre>
   *
   * In addition to finding the correct critical path, this test asserts that we correctly identify
   * b as the sibling sub-critical path between a and c.
   */
  @Test
  @SuppressWarnings("unused")
  public void siblingDeltaMagnitude() {
    CriticalPathBuilder builder = new CriticalPathBuilder();
    BuildRule b = execRule(builder, "b", 5);
    BuildRule c = execRule(builder, "c", 9);
    BuildRule d = execRule(builder, "d", 4);
    BuildRule a = execRule(builder, "a", 4, b, c, d);

    ImmutableList<ReportableCriticalPathNode> criticalPath = builder.getCriticalPath();
    Assert.assertEquals(2, criticalPath.size());

    Assert.assertEquals(c.getBuildTarget(), criticalPath.get(0).getTarget());
    Assert.assertEquals(9, criticalPath.get(0).getExecutionTimeMilliseconds());
    Assert.assertEquals(9, criticalPath.get(0).getPathCostMilliseconds());

    // Key part of this test: although the critical path is c -> a, the reportable critical path
    // node also indicates that the secondary critical path is b -> a, with a delta of 4 (e.g. this
    // path is 4 ms shorter than the critical path)
    Assert.assertTrue(criticalPath.get(0).getClosestSiblingTarget().isPresent());
    Assert.assertEquals(b.getBuildTarget(), criticalPath.get(0).getClosestSiblingTarget().get());
    Assert.assertEquals(4, (long) criticalPath.get(0).getClosestSiblingExecutionTimeDelta().get());

    Assert.assertEquals(a.getBuildTarget(), criticalPath.get(1).getTarget());
    Assert.assertEquals(4, criticalPath.get(1).getExecutionTimeMilliseconds());
    Assert.assertEquals(13, criticalPath.get(1).getPathCostMilliseconds());
  }

  /**
   * Test for finding the critical path in the following action graph:
   *
   * <pre>
   *
   *            a (exec: 4) --\ /-- e (exec: 1)
   *          /     |         /\     |
   *   b (exec: 5)  c (exec: 9) d (exec: 4)
   *
   * </pre>
   *
   * Despite processing e last, we should still identify `b` as the sibling sub-critical path
   * between a and c.
   */
  @Test
  @SuppressWarnings("unused")
  public void siblingDeltaMagnitudeWithAlternatePath() {
    CriticalPathBuilder builder = new CriticalPathBuilder();
    BuildRule b = execRule(builder, "b", 5);
    BuildRule c = execRule(builder, "c", 9);
    BuildRule d = execRule(builder, "d", 4);
    BuildRule a = execRule(builder, "a", 4, b, c, d);
    BuildRule e = execRule(builder, "e", 1, c, d);

    ImmutableList<ReportableCriticalPathNode> criticalPath = builder.getCriticalPath();
    Assert.assertEquals(2, criticalPath.size());

    Assert.assertEquals(c.getBuildTarget(), criticalPath.get(0).getTarget());
    Assert.assertEquals(9, criticalPath.get(0).getExecutionTimeMilliseconds());
    Assert.assertEquals(9, criticalPath.get(0).getPathCostMilliseconds());

    Assert.assertTrue(criticalPath.get(0).getClosestSiblingTarget().isPresent());
    Assert.assertEquals(b.getBuildTarget(), criticalPath.get(0).getClosestSiblingTarget().get());
    Assert.assertEquals(4, (long) criticalPath.get(0).getClosestSiblingExecutionTimeDelta().get());

    Assert.assertEquals(a.getBuildTarget(), criticalPath.get(1).getTarget());
    Assert.assertEquals(4, criticalPath.get(1).getExecutionTimeMilliseconds());
    Assert.assertEquals(13, criticalPath.get(1).getPathCostMilliseconds());
  }

  private static BuildRule execRule(
      CriticalPathBuilder builder, String targetName, int execTime, BuildRule... dependencies) {
    BuildRule buildRule =
        new FakeBuildRule(
            BuildTargetFactory.newInstance("//:" + targetName),
            ImmutableSortedSet.copyOf(dependencies));
    builder.onBuildRuleCompletedExecution(buildRule, execTime);
    builder.onBuildRuleFinalized(buildRule, 0L);
    return buildRule;
  }

  private static BuildRule cachedRule(
      CriticalPathBuilder builder, String targetName, BuildRule... dependencies) {
    BuildRule buildRule =
        new FakeBuildRule(
            BuildTargetFactory.newInstance("//:" + targetName),
            ImmutableSortedSet.copyOf(dependencies));
    builder.onBuildRuleFinalized(buildRule, 0L);
    return buildRule;
  }
}
