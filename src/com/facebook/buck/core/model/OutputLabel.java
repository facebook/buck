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
import java.util.Optional;

/** Type-safe wrapper for a target output label */
public class OutputLabel implements Comparable<OutputLabel> {
  public static final OutputLabel DEFAULT = new OutputLabel();

  private final String label;

  private OutputLabel() {
    label = "";
  }

  public OutputLabel(String label) {
    Preconditions.checkArgument(!label.isEmpty());
    this.label = label;
  }

  @Override
  public String toString() {
    return label.isEmpty() ? "<default>" : label;
  }

  public Optional<String> getLabel() {
    return label.isEmpty() ? Optional.empty() : Optional.of(label);
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
