/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableSet;

import java.util.List;

public abstract class TestRunEvent extends AbstractBuckEvent {

  private TestRunEvent(int secret) {
    super(EventKey.of("TestRunEvent", secret));
  }

  public static Started started(
      boolean isRunAllTests,
      TestSelectorList testSelectorList,
      boolean shouldExplainTestSelectorList,
      ImmutableSet<String> targets) {
    return new Started(
        targets.hashCode(),
        isRunAllTests,
        testSelectorList,
        shouldExplainTestSelectorList,
        targets);
  }

  public static Finished finished(
      ImmutableSet<String> targets,
      List<TestResults> completedResults) {
    return new Finished(targets.hashCode(), completedResults);
  }

  public static class Started extends TestRunEvent {
    private final boolean runAllTests;
    @JsonIgnore private TestSelectorList testSelectorList;
    private boolean shouldExplainTestSelectorList;
    private final ImmutableSet<String> targetNames;

    public Started(
        int secret,
        boolean runAllTests,
        TestSelectorList testSelectorList,
        boolean shouldExplainTestSelectorList,
        ImmutableSet<String> targetNames) {
      super(secret);
      this.runAllTests = runAllTests;
      this.testSelectorList = testSelectorList;
      this.shouldExplainTestSelectorList = shouldExplainTestSelectorList;
      this.targetNames = ImmutableSet.copyOf(targetNames);
    }

    public boolean isRunAllTests() {
      return runAllTests;
    }

    public ImmutableSet<String> getTargetNames() {
      return targetNames;
    }

    @Override
    public String getEventName() {
      return "RunStarted";
    }

    @Override
    protected String getValueString() {
      return String.format("%d test targets", targetNames.size());
    }

    public TestSelectorList getTestSelectorList() {
      return testSelectorList;
    }

    public boolean shouldExplainTestSelectorList() {
      return shouldExplainTestSelectorList;
    }
  }

  public static class Finished extends TestRunEvent {

    private final List<TestResults> completedResults;

    public Finished(int secret, List<TestResults> completedResults) {
      super(secret);
      this.completedResults = completedResults;
    }

    @Override
    public String getEventName() {
      return "RunComplete";
    }

    @Override
    protected String getValueString() {
      return completedResults.toString();
    }

    public List<TestResults> getResults() {
      return completedResults;
    }
  }
}
