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

import static org.easymock.EasyMock.captureBoolean;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.test.TestResults;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.Capture;

import java.util.Comparator;

class MockingDSL {
  private MockingDSL() {}

  static BuildRule mockLibrary(String baseName, String shortName) {
    BuildRule mock = createMock(BuildRule.class);
    BuildTarget buildTarget = BuildTarget.builder(baseName, shortName).build();
    expect(mock.getBuildTarget()).andReturn(buildTarget).anyTimes();
    return mock;
  }

  static TestRule mockTest(String baseName, String shortName) {
    TestRule mock = createMock(TestRule.class);
    BuildTarget buildTarget = BuildTarget.builder(baseName, shortName).build();
    expect(mock.getBuildTarget()).andReturn(buildTarget).anyTimes();
    return mock;
  }

  static TestResults passTests() {
    Capture<Boolean> hasPassingDependencies = newCapture();
    return testResults(true, hasPassingDependencies);
  }

  static TestResults failTestsAndCapture(Capture<Boolean> param) {
    return testResults(false, param);
  }

  private static TestResults testResults(
      boolean isSuccess,
      Capture<Boolean> capturesHasPassingDependencies) {
    TestResults mock = createMock(TestResults.class);
    expect(mock.isSuccess()).andReturn(isSuccess).anyTimes();
    mock.setDependenciesPassTheirTests(captureBoolean(capturesHasPassingDependencies));
    replay(mock);
    return mock;
  }

  static void deps(BuildRule buildRule, BuildRule... dependencies) {
    Comparator<BuildRule> comparator = new Comparator<BuildRule>() {
      @Override
      public int compare(BuildRule o1, BuildRule o2) {
        return o1.hashCode() - o2.hashCode();
      }
    };
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.orderedBy(comparator);
    for (BuildRule dependency : dependencies) {
      builder.add(dependency);
    }
    ImmutableSortedSet<BuildRule> dependenciesSet = builder.build();
    expect(buildRule.getDeps()).andReturn(dependenciesSet).anyTimes();
  }

  static void sourceUnderTest(TestRule testRule, BuildRule... buildRules) {
    expect(testRule.getSourceUnderTest()).andReturn(ImmutableSet.copyOf(buildRules)).anyTimes();
  }
}
