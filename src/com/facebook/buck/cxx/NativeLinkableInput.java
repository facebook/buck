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

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

/**
 * A class containing inputs to be passed to the native linker.  Dependencies (e.g. C++ libraries)
 * of a top-level native linkable rule (e.g. C++ binary) can use this to contribute components to
 * the final link.
 */
public class NativeLinkableInput {

  // Rules that contribute built inputs (e.g. archives, object files) to the arguments below.
  private final ImmutableSet<BuildTarget> targets;

  // Inputs used by linker.
  private final ImmutableList<Path> inputs;

  // Arguments to pass to the linker.  In the future it'd be nice to make this more aware of
  // the differences between archives, objects, flags, etc.
  private final ImmutableList<String> args;

  public NativeLinkableInput(
      ImmutableSet<BuildTarget> targets,
      ImmutableList<Path> inputs,
      ImmutableList<String> args) {

    this.targets = Preconditions.checkNotNull(targets);
    this.inputs = Preconditions.checkNotNull(inputs);
    this.args = Preconditions.checkNotNull(args);
  }

  /**
   * Combine, in order, several {@link NativeLinkableInput} objects into a single one.
   */
  public static NativeLinkableInput concat(Iterable<NativeLinkableInput> items) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    ImmutableList.Builder<Path> inputs = ImmutableList.builder();
    ImmutableList.Builder<String> args = ImmutableList.builder();

    for (NativeLinkableInput item : items) {
      targets.addAll(item.getTargets());
      inputs.addAll(item.getInputs());
      args.addAll(item.getArgs());
    }

    return new NativeLinkableInput(
        targets.build(),
        inputs.build(),
        args.build());
  }

  public ImmutableSet<BuildTarget> getTargets() {
    return targets;
  }

  public ImmutableList<Path> getInputs() {
    return inputs;
  }

  public ImmutableList<String> getArgs() {
    return args;
  }

  @Override
  public boolean equals(Object o) {

    if (this == o) {
      return true;
    }

    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    NativeLinkableInput that = (NativeLinkableInput) o;

    if (!args.equals(that.args)) {
      return false;
    }

    if (!targets.equals(that.targets)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(targets, args);
  }

}
