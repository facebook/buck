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

package com.facebook.buck.test.selectors;

/**
 * Errors specific to parsing test selectors.
 *
 * While this could reasonably be a subclass of
 * {@link com.facebook.buck.util.HumanReadableException} our desire to keep the dependencies of
 * this package to a minimum means we'll subclass {@link RuntimeException} instead and convert
 * to a {@link com.facebook.buck.util.HumanReadableException} elsewhere.
 */
@SuppressWarnings("serial")
public class TestSelectorParseException extends RuntimeException {
  public TestSelectorParseException(String message) {
    super(message);
  }

  public TestSelectorParseException(String message, Exception cause) {
    super(message, cause);
  }
}
