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

package com.facebook.buck.test.external;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.ExitCode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

/** Base class for events related to external test runs. */
public abstract class ExternalTestRunEvent extends AbstractBuckEvent implements WorkAdvanceEvent {

  private ExternalTestRunEvent(int secret) {
    super(EventKey.slowValueKey("ExternalTestRunEvent", secret));
  }

  @JsonIgnore
  public String getCategory() {
    return "external_test_run";
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

  public static Finished finished(Set<String> targets, ExitCode exitCode) {
    return new Finished(targets.hashCode(), exitCode);
  }

  public static class Started extends ExternalTestRunEvent {
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

    @Override
    public String getEventName() {
      return "ExternalTestRunStarted";
    }

    @Override
    protected String getValueString() {
      return String.format("%d test targets", targetNames.size());
    }

    public TestSelectorList getTestSelectorList() {
      return testSelectorList;
    }

    public ImmutableSet<String> getTargetNames() {
      return targetNames;
    }

    public boolean shouldExplainTestSelectorList() {
      return shouldExplainTestSelectorList;
    }
  }

  public static class Finished extends ExternalTestRunEvent {

    private final ExitCode exitCode;

    public Finished(int secret, ExitCode exitCode) {
      super(secret);
      this.exitCode = exitCode;
    }

    @Override
    public String getEventName() {
      return "ExternalTestRunFinished";
    }

    @Override
    protected String getValueString() {
      return String.valueOf(exitCode.getCode());
    }

    public ExitCode getExitCode() {
      return exitCode;
    }
  }
}
