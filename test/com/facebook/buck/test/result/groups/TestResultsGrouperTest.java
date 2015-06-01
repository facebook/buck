/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.test.result.groups;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeTestRule;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Test;

public class TestResultsGrouperTest {

  private TestResultsGrouper grouper;

  @Before
  public void setUp() {
    grouper = new TestResultsGrouper(ImmutableSet.<TestRule>of());
  }

  @Test
  public void testGetAllDependencies() {
    /**
     * No circular dependencies, but parts of the graph are visited twice through various routes.
     *
     *    A
     *   / \
     *  B   C
     *   \ / \
     *    D   E
     *     \ / \
     *      F   G -> (D -> F)
     */
    FakeTestRule f = getFakeTestRule("f");
    FakeTestRule d = getFakeTestRule("d", f);
    FakeTestRule g = getFakeTestRule("g", d);
    FakeTestRule e = getFakeTestRule("e", f, g);
    FakeTestRule c = getFakeTestRule("c", d, e);
    FakeTestRule b = getFakeTestRule("b", d);
    FakeTestRule a = getFakeTestRule("a", b, c);

    assertDependencies(g, /* => */ g, d, f);
    assertDependencies(f, /* => */ f);
    assertDependencies(e, /* => */ e, f, g, d);
    assertDependencies(d, /* => */ d, f);
    assertDependencies(c, /* => */ c, d, e, f, g);
    assertDependencies(b, /* => */ b, d, f);
    assertDependencies(a, /* => */ a, b, c, d, e, f, g);
  }

  private void assertDependencies(BuildRule root, BuildRule... dependencies) {
    assertEquals(ImmutableSet.copyOf(dependencies), grouper.getAllDependencies(root));
  }

  private FakeTestRule getFakeTestRule(String suffix, BuildRule... dependencies) {
    String name = String.format("//:%s", suffix);
    return new FakeTestRule(
        ImmutableSet.<Label>of(),
        BuildTargetFactory.newInstance(name),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.copyOf(dependencies));
  }
}
