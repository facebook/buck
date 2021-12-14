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
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Optional;

/** BuckEvent to broadcast selectively information about External Test Runners used. */
public abstract class ExternalTestRunnerSelectionEvent extends AbstractBuckEvent {

  private final Iterable<String> testRules;
  private final boolean allRulesWithinAllowedPrefixes;
  private final Optional<String> customRunner;

  public Iterable<String> getTestRules() {
    return testRules;
  }

  public boolean isAllRulesWithinAllowedPrefixes() {
    return allRulesWithinAllowedPrefixes;
  }

  public Optional<String> getCustomRunner() {
    return customRunner;
  }

  public abstract String getShortEventName();

  private ExternalTestRunnerSelectionEvent(
      int secret,
      Iterable<String> testRules,
      boolean allRulesWithinAllowedPrefixes,
      Optional<String> customRunner) {
    super(EventKey.slowValueKey("ExternalTestRunnerSelectionEvent", secret));
    this.testRules = testRules;
    this.allRulesWithinAllowedPrefixes = allRulesWithinAllowedPrefixes;
    this.customRunner = customRunner;
  }

  public static ExternalTestRunnerSelectionEvent externalTestRunnerOverridden(
      Iterable<String> testRuleNames,
      boolean allRulesWithinAllowedPrefixes,
      Optional<String> customRunner) {
    return new OverrideEvent(testRuleNames, allRulesWithinAllowedPrefixes, customRunner);
  }

  @JsonIgnore
  public String getCategory() {
    return "external_test_runner_selection";
  }

  /** The extern test runner defined at project level has been overridden */
  public static class OverrideEvent extends ExternalTestRunnerSelectionEvent {

    private OverrideEvent(
        Iterable<String> testRules,
        boolean allRulesWithinAllowedPrefixes,
        Optional<String> customRunner) {
      super(testRules.hashCode(), testRules, allRulesWithinAllowedPrefixes, customRunner);
    }

    @Override
    protected String getValueString() {
      return getCustomRunner().toString();
    }

    @Override
    public String getEventName() {
      return "ExternalTestRunner.Override";
    }

    @Override
    public String getShortEventName() {
      return "Override";
    }
  }
}
