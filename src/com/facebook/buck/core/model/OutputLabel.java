/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.model;

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
public class OutputLabel implements Comparable<OutputLabel> {

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
  public static final OutputLabel DEFAULT = new OutputLabel();

  private final String label;

  private OutputLabel() {
    label = "";
  }

  // TODO(irenewchen): Hide this constructor and make a static factory method that takes
  // Optional<String>
  public OutputLabel(String label) {
    Preconditions.checkArgument(!label.isEmpty());
    this.label = label;
  }

  public boolean isDefault() {
    return this == DEFAULT;
  }

  @Override
  public String toString() {
    return label.isEmpty() ? "<default>" : label;
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
}
