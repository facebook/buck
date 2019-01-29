/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.cell;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Optional;

/** Exception that represents a build target attempting to use a cell which doesn't exist. */
public class UnknownCellException extends HumanReadableException {

  private static final String generateErrorMessage(
      Optional<String> cellName, ImmutableSet<String> validCellNames) {
    if (!cellName.isPresent()) {
      return "Cannot determine path of the root cell";
    } else {
      List<String> suggestions = generateSuggestions(cellName, validCellNames);
      return String.format(
          "Unknown cell: %s. Did you mean one of %s instead?",
          cellName.get(), suggestions.isEmpty() ? validCellNames : suggestions);
    }
  }

  private static final ImmutableList<String> generateSuggestions(
      Optional<String> cellName, ImmutableSet<String> validCellNames) {
    if (!cellName.isPresent()) {
      return ImmutableList.of();
    }

    List<String> suggestions =
        MoreStrings.getSpellingSuggestions(cellName.get(), validCellNames, 2);

    if (!suggestions.isEmpty()) {
      return ImmutableList.copyOf(suggestions);
    }

    return validCellNames.stream().sorted().collect(ImmutableList.toImmutableList());
  }

  public UnknownCellException(Optional<String> cellName, ImmutableSet<String> validCellNames) {
    super(generateErrorMessage(cellName, validCellNames));
  }
}
