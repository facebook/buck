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

package com.facebook.buck.parser;

import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.TestRule;

public class AssociatedRulePredicates {
  private static final AssociatedRulePredicate ALWAYS_TRUE = new AssociatedRulePredicate() {
    @Override
    public boolean isMatch(BuildRule buildRule, ActionGraph actionGraph) {
      return true;
    }
  };

  private AssociatedRulePredicates() {}

  public static AssociatedRulePredicate alwaysTrue() {
    return ALWAYS_TRUE;
  }

  public static AssociatedRulePredicate associatedTestsRules() {
    return new AssociatedRulePredicate() {
      @Override
      public boolean isMatch(BuildRule buildRule, ActionGraph actionGraph) {
        TestRule testRule;
        if (buildRule instanceof TestRule) {
          testRule = (TestRule) buildRule;
        } else {
          return false;
        }
        for (BuildRule buildRuleUnderTest : testRule.getSourceUnderTest()) {
          if (actionGraph.findBuildRuleByTarget(buildRuleUnderTest.getBuildTarget()) != null) {
            return true;
          }
        }
        return false;
      }
    };
  }
}
