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

package com.facebook.buck.core.model;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.google.common.base.Preconditions;
import java.util.Objects;

/**
 * Type-safe wrapper for a target output label.
 *
 * <p>A build target can return different outputs groups, with each output group potentially
 * containing zero or more outputs. A target output label is used to refer to a particular output
 * group in a target.
 *
 * <p>With providers, the output label maps to named output groups in {@link
 * com.facebook.buck.core.rules.providers.lib.DefaultInfo}. With the old action graph, each rule
 * should maintain an internal mapping of output labels to output groups.
 *
 * <p>A {@link #DEFAULT} output label is provided when the user has not specified a named output
 * label in a build target. For example, "//foo:bar" would be associated with the default output
 * label, whereas "//foo:bar[baz] would be associated with the named label "baz".
 */
public class OutputLabel implements Comparable<OutputLabel>, AddsToRuleKey {

  /**
   * Label denoting the default output group rather than a named output group.
   *
   * <p>With providers, this would be the default outputs from {@link
   * com.facebook.buck.core.rules.providers.lib.DefaultInfo}. The default outputs can be the set of
   * all possible outputs, a subset of all possible outputs, or an empty output group.
   *
   * <p>With the old action graph, this would be rule-dependent depending on how the rule implements
   * {@link com.facebook.buck.core.rules.attr.HasMultipleOutputs#getSourcePathToOutput()}.
   */
  private static final OutputLabel DEFAULT = new OutputLabel();

  private static final String USER_FACING_DEFAULT_LABEL = "DEFAULT";

  @AddToRuleKey private final String label;

  private OutputLabel() {
    label = "";
  }

  private OutputLabel(String label) {
    Preconditions.checkArgument(!label.isEmpty(), "Output label cannot be empty");
    Preconditions.checkArgument(
        !label.equals(USER_FACING_DEFAULT_LABEL),
        "%s is a restricted output label. Use another output label",
        USER_FACING_DEFAULT_LABEL);
    this.label = label;
  }

  /**
   * Returns an output label wrapping the given String. Use {@link #defaultLabel()} to get the
   * default empty output label.
   *
   * @throws IllegalArgumentException if the given {@code label} is an empty string
   */
  public static OutputLabel of(String label) {
    return new OutputLabel(label);
  }

  /** Returns the output label for default outputs. */
  public static OutputLabel defaultLabel() {
    return DEFAULT;
  }

  public boolean isDefault() {
    return this == DEFAULT;
  }

  private String getLabel() {
    return label;
  }

  @Override
  public String toString() {
    return label.isEmpty() ? USER_FACING_DEFAULT_LABEL : label;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    OutputLabel that = (OutputLabel) o;
    return label.equals(that.label);
  }

  @Override
  public int hashCode() {
    return Objects.hash(label);
  }

  @Override
  public int compareTo(OutputLabel o) {
    return this.label.compareTo(o.label);
  }

  public static OutputLabel.Internals internals() {
    return Internals.INSTANCE;
  }

  /**
   * Provides access to internal implementation details of OutputLabels. Using this should be
   * avoided.
   */
  public static class Internals {
    private static final OutputLabel.Internals INSTANCE = new OutputLabel.Internals();

    public String getLabel(OutputLabel outputLabel) {
      return outputLabel.getLabel();
    }
  }
}
