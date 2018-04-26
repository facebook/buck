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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.annotations.VisibleForTesting;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.immutables.value.Value;

/** Macro that resolves to the output location of a build rule. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractLocationMacro extends BuildTargetMacro {

  private static final Pattern BUILD_TARGET_WITH_SUPPLEMENTARY_OUTPUT_PATTERN =
      Pattern.compile("^(?<target>.+?)(?:\\[(?<output>[A-Za-z0-9_./-]+)\\])?$");

  @Override
  public abstract BuildTarget getTarget();

  @Value.Parameter(order = 2)
  abstract Optional<String> getSupplementaryOutputIdentifier();

  /** Shorthand for constructing a LocationMacro referring to the main output. */
  @VisibleForTesting
  public static LocationMacro of(BuildTarget buildTarget) {
    return LocationMacro.of(buildTarget, Optional.empty());
  }

  /**
   * Split a build target string with optional supplementary output pattern.
   *
   * @return The target part and the supplementary output part of the string.
   *     <p>Note: this function simply looks for a trailing "[foo]", it does not validate that the
   *     string starts with a valid build target.
   */
  public static SplitResult splitSupplementaryOutputPart(String targetish) {
    Matcher matcher = BUILD_TARGET_WITH_SUPPLEMENTARY_OUTPUT_PATTERN.matcher(targetish);
    String outputNamePart = matcher.matches() ? matcher.group("output") : null;
    return new SplitResult(matcher.group("target"), Optional.ofNullable(outputNamePart));
  }

  /** Result object of {@link #splitSupplementaryOutputPart}. */
  public static class SplitResult {
    public final String target;
    public final Optional<String> supplementaryOutput;

    private SplitResult(String target, Optional<String> supplementaryOutput) {
      this.target = target;
      this.supplementaryOutput = supplementaryOutput;
    }
  }
}
