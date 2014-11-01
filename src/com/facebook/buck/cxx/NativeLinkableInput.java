/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import javax.annotation.Nullable;

/**
 * A class containing inputs to be passed to the native linker.  Dependencies (e.g. C++ libraries)
 * of a top-level native linkable rule (e.g. C++ binary) can use this to contribute components to
 * the final link.
 */
public class NativeLinkableInput {

  // Inputs used by linker.
  private final ImmutableList<SourcePath> inputs;

  // Arguments to pass to the linker.  In the future it'd be nice to make this more aware of
  // the differences between archives, objects, flags, etc.
  private final ImmutableList<String> args;

  public NativeLinkableInput(
      ImmutableList<SourcePath> inputs,
      ImmutableList<String> args) {

    this.inputs = inputs;
    this.args = args;
  }

  /**
   * Combine, in order, several {@link NativeLinkableInput} objects into a single one.
   */
  public static NativeLinkableInput concat(Iterable<NativeLinkableInput> items) {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();
    ImmutableList.Builder<String> args = ImmutableList.builder();

    for (NativeLinkableInput item : items) {
      inputs.addAll(item.getInputs());
      args.addAll(item.getArgs());
    }

    return new NativeLinkableInput(
        inputs.build(),
        args.build());
  }

  public ImmutableList<SourcePath> getInputs() {
    return inputs;
  }

  public ImmutableList<String> getArgs() {
    return args;
  }

  @Override
  public boolean equals(@Nullable Object o) {

    if (this == o) {
      return true;
    }

    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    NativeLinkableInput that = (NativeLinkableInput) o;

    if (!inputs.equals(that.inputs)) {
      return false;
    }

    if (!args.equals(that.args)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(inputs, args);
  }

}
