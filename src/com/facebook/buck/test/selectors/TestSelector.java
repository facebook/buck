/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.test.selectors;

/**
 * A way of matching a test-method in a test-class, and saying whether or not to include any matches
 * in a test run.
 */
public interface TestSelector {
  String getRawSelector();

  String getExplanation();

  boolean isInclusive();

  boolean isMatchAnyClass();

  boolean isMatchAnyMethod();

  /**
   * Whether this {@link TestSelector} matches the given {@link TestDescription}. A class or method
   * name being null in the {@link TestDescription} means that it will match anything.
   *
   * @param description the {@link TestDescription} to match
   * @return true if this selector matches the given {@link TestDescription}
   */
  boolean matches(TestDescription description);

  boolean matchesClassName(String className);
}
