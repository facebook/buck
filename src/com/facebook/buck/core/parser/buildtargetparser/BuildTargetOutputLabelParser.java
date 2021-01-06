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

package com.facebook.buck.core.parser.buildtargetparser;

import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Utility class for parsing output labels from build targets. */
public class BuildTargetOutputLabelParser {
  private static final String OUTPUT_LABEL_START_INDICATOR = "[";
  private static final String OUTPUT_LABEL_END_INDICATOR = "]";

  private BuildTargetOutputLabelParser() {}

  /**
   * Returns the fully-qualified or relative build target name and its associated output label, if
   * any.
   */
  public static TargetWithOutputLabel getBuildTargetNameWithOutputLabel(String targetName) {
    if (!targetName.contains(OUTPUT_LABEL_START_INDICATOR)
        && !targetName.contains(OUTPUT_LABEL_END_INDICATOR)) {
      return ImmutableTargetWithOutputLabel.of(targetName, OutputLabel.defaultLabel());
    }
    int outputLabelStartIndex = targetName.indexOf(OUTPUT_LABEL_START_INDICATOR);
    int outputLabelEndIndex = targetName.indexOf(OUTPUT_LABEL_END_INDICATOR);
    checkValid(outputLabelStartIndex, outputLabelEndIndex, targetName);
    return ImmutableTargetWithOutputLabel.of(
        targetName.substring(0, outputLabelStartIndex),
        OutputLabel.of(targetName.substring(outputLabelStartIndex + 1, targetName.length() - 1)));
  }

  private static void checkValid(
      int outputLabelStartIndex, int outputLabelEndIndex, String targetName) {
    if (outputLabelStartIndex >= outputLabelEndIndex) {
      throw new BuildTargetParseException(
          String.format("Could not parse output label for %s", targetName));
    }
    if (outputLabelEndIndex != targetName.length() - 1) {
      throw new BuildTargetParseException(
          String.format("Output label must come last in %s", targetName));
    }
  }

  /**
   * Wrapper for a build target and its output label, if present.
   *
   * <p>Since this is a convenience wrapper class to avoid using {@link
   * com.facebook.buck.util.types.Pair} everywhere we need to associate a build target with its
   * output label, the type of build target is intentionally left free-form to accommodate the
   * myriad build target types we have.
   */
  @BuckStyleValue
  public abstract static class TargetWithOutputLabel {
    public abstract String getTargetName();

    public abstract OutputLabel getOutputLabel();
  }
}
