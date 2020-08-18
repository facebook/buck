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

import com.facebook.buck.downward.model.ChromeTraceEvent;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;

/**
 * {@link org.hamcrest.Matcher} for {@link ChromeTraceEvent} instances that do not compare chrome
 * trace events' event ID when testing for a match.
 */
public class ChromeTraceEventMatcher extends BaseMatcher<ChromeTraceEvent> {

  private final ChromeTraceEvent expected;

  private ChromeTraceEventMatcher(ChromeTraceEvent expected) {
    this.expected = expected;
  }

  public static ChromeTraceEventMatcher equalsTraceEvent(ChromeTraceEvent expected) {
    return new ChromeTraceEventMatcher(expected);
  }

  @Override
  public boolean matches(Object actual) {
    if (actual instanceof ChromeTraceEvent) {
      ChromeTraceEvent object = (ChromeTraceEvent) actual;
      return expected.getCategory().equals(object.getCategory())
          && expected.getTitle().equals(object.getTitle())
          && expected.getDataMap().equals(object.getDataMap())
          && expected.getStatus().equals(object.getStatus());
    }
    return false;
  }

  @Override
  public void describeTo(Description description) {
    description.appendText(String.format("<ChromeTraceEvent:%s>", expected));
  }
}
