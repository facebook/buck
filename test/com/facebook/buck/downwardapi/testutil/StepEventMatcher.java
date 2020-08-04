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

package com.facebook.buck.downwardapi.testutil;

import com.facebook.buck.downward.model.StepEvent;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;

/**
 * {@link org.hamcrest.Matcher} for {@link StepEvent} instances that do not compare step events'
 * event ID when testing for a match.
 */
public class StepEventMatcher extends BaseMatcher<StepEvent> {

  private final StepEvent expected;

  private StepEventMatcher(StepEvent expected) {
    this.expected = expected;
  }

  public static StepEventMatcher equalsStepEvent(StepEvent expected) {
    return new StepEventMatcher(expected);
  }

  @Override
  public boolean matches(Object actual) {
    if (actual instanceof StepEvent) {
      StepEvent object = (StepEvent) actual;
      return expected.getDescription().equals(object.getDescription())
          && expected.getStepStatus().equals(object.getStepStatus())
          && expected.getStepType().equals(object.getStepType());
    }
    return false;
  }

  @Override
  public void describeTo(Description description) {
    description.appendText(String.format("<StepEvent:%s>", expected));
  }
}
