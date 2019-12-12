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

package com.facebook.buck.tools.consistency;

import java.util.concurrent.atomic.AtomicInteger;

/** Keeps track of the number of differences that the rule key differ has encountered so far */
public class DifferState {
  /** Whether or not changes have been found so far by the differ */
  public enum DiffResult {
    CHANGES_FOUND,
    NO_CHANGES_FOUND,
  }

  /** The maximum number of differences have been found */
  public class MaxDifferencesException extends Exception {
    MaxDifferencesException() {
      super(String.format("Stopping after finding %s differences", maxDifferences));
    }
  }

  public static final int INFINITE_DIFFERENCES = -1;

  private AtomicInteger foundDifferences;
  private final int maxDifferences;

  public DifferState(int maxDifferences) {
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

  /** Determine whether or not any changes have been made */
  public DiffResult hasChanges() {
    return foundDifferences.get() > 0 ? DiffResult.CHANGES_FOUND : DiffResult.NO_CHANGES_FOUND;
  }
}
