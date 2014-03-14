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

package com.facebook.buck.parser;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.util.Map;

public class RawRulePredicates {

  private static final RawRulePredicate alwaysTrue = new RawRulePredicate() {

    @Override
    public boolean isMatch(Map<String, Object> rawParseData,
        BuildRuleType buildRuleType, BuildTarget buildTarget) {
      return true;
    }

  };

  private static final RawRulePredicate alwaysFalse = new RawRulePredicate() {

    @Override
    public boolean isMatch(Map<String, Object> rawParseData,
                           BuildRuleType buildRuleType, BuildTarget buildTarget) {
      return false;
    }

  };

  private RawRulePredicates() {}

  public static RawRulePredicate alwaysTrue() {
    return alwaysTrue;
  }

  public static RawRulePredicate alwaysFalse() {
    return alwaysFalse;
  }

  public static RawRulePredicate matchName(final String fullyQualifiedName) {
    Preconditions.checkNotNull(fullyQualifiedName);
    return new RawRulePredicate() {
      @Override
      public boolean isMatch(
          Map<String, Object> rawParseData,
          BuildRuleType buildRuleType,
          BuildTarget buildTarget) {
        return fullyQualifiedName.equals(buildTarget.getFullyQualifiedName());
      }
    };
  }

  public static RawRulePredicate matchBuildRuleType(final BuildRuleType type) {
    return new RawRulePredicate() {
      @Override
      public boolean isMatch(
          Map<String, Object> rawParseData,
          BuildRuleType buildRuleType, BuildTarget buildTarget) {
        return buildRuleType == type;
      }
    };
  }

  public static RawRulePredicate matchBuildTargets(final ImmutableSet<BuildTarget> targets) {
    return new RawRulePredicate() {
      @Override
      public boolean isMatch(
          Map<String, Object> rawParseData,
          BuildRuleType buildRuleType,
          BuildTarget buildTarget) {
        return targets.contains(buildTarget);
      }
    };
  }

  public static final RawRulePredicate isTestRule() {
    return new RawRulePredicate() {
      @Override
      public boolean isMatch(
          Map<String, Object> rawParseData,
          BuildRuleType buildRuleType,
          BuildTarget buildTarget) {
        return buildRuleType.isTestRule();
      }
    };
  }
}
