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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.java.FakeJavaLibraryRule;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import org.junit.Test;

import java.util.List;

public class AbstractDependencyVisitorTest {

  private static final String BUILD_RULE_BASE_NAME = "//java";

  @Test
  public void testIsBreadthFirst() {
    // The dependency graph of build rules being built up is as follows:
    //
    //                       J
    //                /    /   \     \
    //              F    G       H     I
    //            /   \        /   \     \
    //          E       D    C       B     A
    //
    BuildRule ruleA = createRule("A");
    BuildRule ruleB = createRule("B");
    BuildRule ruleC = createRule("C");
    BuildRule ruleD = createRule("D");
    BuildRule ruleE = createRule("E");

    BuildRule ruleF = createRule("F", ruleE, ruleD);
    BuildRule ruleG = createRule("G");
    BuildRule ruleH = createRule("H", ruleC, ruleB);
    BuildRule ruleI = createRule("I", ruleA);

    BuildRule initialRule = createRule("J", ruleF, ruleG, ruleH, ruleI);

    final List<BuildRule> buildRuleTraversalOrder = Lists.newArrayList();
    new AbstractDependencyVisitor(initialRule) {
      @Override
      public boolean visit(BuildRule rule) {
        buildRuleTraversalOrder.add(rule);
        return true;
      }
    }.start();

    assertEquals(
        "Dependencies should be explored depth-first, using lexicographic ordering to break ties",
        ImmutableList.of(initialRule,
            ruleF,
            ruleG,
            ruleH,
            ruleI,
            ruleD,
            ruleE,
            ruleB,
            ruleC,
            ruleA),
        buildRuleTraversalOrder);
  }

  private FakeJavaLibraryRule createRule(String name, BuildRule... deps) {
    ImmutableSet<BuildTargetPattern> visibilityPatterns = ImmutableSet.of();
    FakeJavaLibraryRule rule = new FakeJavaLibraryRule(
        BuildRuleType.JAVA_LIBRARY,
        BuildTargetFactory.newInstance(BUILD_RULE_BASE_NAME, name),
        ImmutableSortedSet.copyOf(deps),
        visibilityPatterns);
    return rule;
  }
}
