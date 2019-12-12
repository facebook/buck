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

import com.facebook.buck.tools.consistency.DifferState.MaxDifferencesException;
import com.facebook.buck.tools.consistency.TargetHashFileParser.ParsedTargetsFile;
import com.google.common.collect.Sets;
import java.util.Set;

/** Finds the differences between two sets of target names and hashes and prints them out */
public class TargetsDiffer {
  private final DiffPrinter diffPrinter;
  private final DifferState differState;

  /**
   * Creates an instance of {@link TargetsDiffer}
   *
   * @param diffPrinter The printer to output changes
   * @param differState The current state
   */
  public TargetsDiffer(DiffPrinter diffPrinter, DifferState differState) {
    this.diffPrinter = diffPrinter;
    this.differState = differState;
  }

  /**
   * Prints the differences between two target graphs, including newly added, removed, and changed
   * targets and hashes
   *
   * @param originalFile The original set of target / hash pairs
   * @param newFile The new set of target / hash pairs
   * @throws MaxDifferencesException If the maximum number of differences (as specified by {@link
   *     #diffPrinter}) is reached
   * @return true if the two targets are different false if they aren't
   */
  public DifferState.DiffResult printDiff(ParsedTargetsFile originalFile, ParsedTargetsFile newFile)
      throws MaxDifferencesException {
    Set<String> targetsInOriginal = originalFile.targetsToHash.keySet();
    Set<String> targetsInNew = newFile.targetsToHash.keySet();
    Set<String> targetsOnlyInOriginal = Sets.difference(targetsInOriginal, targetsInNew);
    Set<String> targetsOnlyInNew = Sets.difference(targetsInNew, targetsInOriginal);
    Set<String> targetsInBoth = Sets.intersection(targetsInOriginal, targetsInNew);

    printRemoved(originalFile, targetsOnlyInOriginal);
    printAdded(newFile, targetsOnlyInNew);
    printChanged(originalFile, newFile, targetsInBoth);
    return differState.hasChanges();
  }

  private void printChanged(
      ParsedTargetsFile originalFile, ParsedTargetsFile newFile, Set<String> targetsInBoth)
      throws MaxDifferencesException {
    for (String target : targetsInBoth) {
      String originalHash = originalFile.targetsToHash.get(target);
      String newHash = newFile.targetsToHash.get(target);
      if (!originalHash.equals(newHash)) {
        differState.incrementDifferenceCount();
        printRemove(target, originalHash);
        printAdd(target, newHash);
      }
    }
  }

  private void printRemoved(ParsedTargetsFile originalFile, Set<String> targetsOnlyInOriginal)
      throws MaxDifferencesException {
    for (String target : targetsOnlyInOriginal) {
      differState.incrementDifferenceCount();
      printRemove(target, originalFile.targetsToHash.get(target));
    }
  }

  private void printAdded(ParsedTargetsFile newFile, Set<String> targetsOnlyInNew)
      throws MaxDifferencesException {
    for (String target : targetsOnlyInNew) {
      differState.incrementDifferenceCount();
      printAdd(target, newFile.targetsToHash.get(target));
    }
  }

  private void printAdd(String target, String hash) {
    diffPrinter.printAdd(String.format("%s %s", target, hash));
  }

  private void printRemove(String target, String hash) {
    diffPrinter.printRemove(String.format("%s %s", target, hash));
  }
}
