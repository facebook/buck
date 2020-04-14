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

package com.facebook.buck.android;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.step.StepFailedException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * In the case of dex overflow while merging pre-dexed libraries, display dex weights for each
 * library
 */
public class DexOverflowError {
  private static final Logger log = Logger.get(DexOverflowError.class);

  private static final String PRIMARY_DEX_OVERFLOW_MESSAGE =
      "Primary dex size exceeds 64k %s ref limit\n"
          + "Use primary dex patterns to exclude classes from the primary dex.";

  private static final String SECONDARY_DEX_OVERFLOW_MESSAGE =
      "Secondary dex size exceeds 64k %s ref limit.\n"
          + "secondary_dex_weight_limit determines the maximum size in bytes of secondary dexes.\n"
          + "Reduce secondary_dex_weight_limit until all secondary dexes are small enough.";

  /** Type of ref overflow for a failed dex step */
  public enum OverflowType {
    METHOD,
    FIELD
  }

  private final Optional<Supplier<List<String>>> primaryDexWeightsSupplier;
  private final OverflowType type;
  private final DxStep dxStep;

  public DexOverflowError(
      Optional<Supplier<List<String>>> primaryDexWeightsSupplier,
      OverflowType type,
      DxStep dxStep) {
    this.primaryDexWeightsSupplier = primaryDexWeightsSupplier;
    this.type = type;
    this.dxStep = dxStep;
  }

  /**
   * Determine the dex overflow type for a failed dexing step, if the step failed due to dex limits
   */
  public static Optional<OverflowType> checkOverflow(StepFailedException exception) {
    if (exception.getStep() instanceof DxStep) {
      int exitCode = exception.getExitCode().orElse(DxStep.FAILURE_EXIT_CODE);
      if (exitCode == DxStep.DEX_FIELD_REFERENCE_OVERFLOW_EXIT_CODE) {
        return Optional.of(OverflowType.FIELD);
      } else if (exitCode == DxStep.DEX_METHOD_REFERENCE_OVERFLOW_EXIT_CODE) {
        return Optional.of(OverflowType.METHOD);
      }
    }
    return Optional.empty();
  }

  /** Return a detailed error message to show the user */
  public String getErrorMessage() {
    String overflowName = type.name().toLowerCase();
    if (dxStep.getOutputDexFile().endsWith("classes.dex")) {
      String overflowMessage = String.format(PRIMARY_DEX_OVERFLOW_MESSAGE, overflowName);
      if (primaryDexWeightsSupplier.isPresent()) {
        List<String> dexWeights = primaryDexWeightsSupplier.get().get();
        return formatDexOverflowMessage(overflowMessage, dexWeights);
      } else {
        return overflowMessage;
      }
    } else {
      return String.format(SECONDARY_DEX_OVERFLOW_MESSAGE, overflowName);
    }
  }

  private String formatDexOverflowMessage(String overflowMessage, List<String> dexWeights) {
    StringBuilder builder = new StringBuilder();
    builder.append(
        overflowMessage
            + "The largest libraries in the primary dex, by number of bytes:\n"
            + "Weight\tDex file path\n");

    builder.append(dexWeights.stream().limit(20).collect(Collectors.joining("\n")));

    if (dexWeights.size() > 20) {
      builder.append("\n... See buck log for full list");
      log.error(String.join("\n", dexWeights));
    }
    return builder.toString();
  }
}
