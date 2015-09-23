/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util;

import org.hamcrest.Matcher;
import org.hamcrest.core.IsEqual;

public class MoreStringsForTests {
  /** Utility class: do not instantiate. */
  private MoreStringsForTests() {}

  private static class IsEqualIgnoringPlatformNewlines extends IsEqual<String> {
    public IsEqualIgnoringPlatformNewlines(String expectedText) {
      super(normalizeNewlines(expectedText));
    }

    @Override
    public boolean matches(Object o) {
      if (o instanceof String) {
        String candidateText = normalizeNewlines((String) o);
        return super.matches(candidateText);
      }
      return false;
    }
  }

  /**
   * A cross-platform matcher for a string with newlines.
   */
  public static Matcher<String> equalToIgnoringPlatformNewlines(String expectedText) {
    return new IsEqualIgnoringPlatformNewlines(expectedText);
  }

  private static String normalizeNewlines(String s) {
    // Windows uses "\r\n", some mac apps use "\r", so standardize on "\n".
    return s.replace("\r\n", "\n").replace("\r", "\n");
  }
}
