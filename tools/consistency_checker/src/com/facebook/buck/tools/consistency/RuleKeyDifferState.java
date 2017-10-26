/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.tools.consistency;

import java.util.concurrent.atomic.AtomicInteger;

/** Keeps track of the number of differences that the rule key differ has encountered so far */
public class RuleKeyDifferState {
  /** The maximum number of differences have been found */
  public class MaxDifferencesException extends Exception {
    MaxDifferencesException() {
      super(String.format("Stopping after finding %s differences", maxDifferences));
    }
  }

  public static final int INFINITE_DIFFERENCES = -1;

  private AtomicInteger foundDifferences;
  private final int maxDifferences;

  public RuleKeyDifferState(int maxDifferences) {
    this.maxDifferences = maxDifferences;
    this.foundDifferences = new AtomicInteger(0);
  }

  /**
   * Increment the difference count. This method is thread safe
   *
   * @throws MaxDifferencesException Thrown if this increment exceeds the maxDifferencesCount
   */
  public void incrementDifferenceCount() throws MaxDifferencesException {
    int differences = foundDifferences.incrementAndGet();
    if (maxDifferences != INFINITE_DIFFERENCES && differences > maxDifferences) {
      throw new MaxDifferencesException();
    }
  }
}
