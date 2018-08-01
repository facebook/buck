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

package com.facebook.buck.cli;

import com.google.common.base.Preconditions;

public enum ProjectTestsMode {

  // No tests.
  WITHOUT_TESTS("without_tests"),
  // With all tests.
  WITH_TESTS("with_tests"),
  // Only the main target tests.
  WITHOUT_DEPENDENCIES_TESTS("without_dependencies_tests"),
  ;

  private final String value;

  ProjectTestsMode(String value) {
    this.value = Preconditions.checkNotNull(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
