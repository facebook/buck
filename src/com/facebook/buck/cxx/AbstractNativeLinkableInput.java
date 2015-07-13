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
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * A class containing inputs to be passed to the native linker.  Dependencies (e.g. C++ libraries)
 * of a top-level native linkable rule (e.g. C++ binary) can use this to contribute components to
 * the final link.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractNativeLinkableInput {

  private static final NativeLinkableInput EMPTY =
      NativeLinkableInput.of(
          ImmutableList.<SourcePath>of(),
          ImmutableList.<String>of(),
          ImmutableSet.<Path>of());

  // Inputs used by linker.
  @Value.Parameter
  public abstract List<SourcePath> getInputs();

  // Arguments to pass to the linker.  In the future it'd be nice to make this more aware of
  // the differences between archives, objects, flags, etc.
  @Value.Parameter
  public abstract List<String> getArgs();

  // Directories where frameworks are stored.
  @Value.Parameter
  public abstract Set<Path> getFrameworkRoots();

  /**
   * Combine, in order, several {@link NativeLinkableInput} objects into a single one.
   */
  public static NativeLinkableInput concat(Iterable<NativeLinkableInput> items) {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();
    ImmutableList.Builder<String> args = ImmutableList.builder();
    ImmutableSet.Builder<Path> frameworkRoots = ImmutableSet.builder();

    for (NativeLinkableInput item : items) {
      inputs.addAll(item.getInputs());
      args.addAll(item.getArgs());
      frameworkRoots.addAll(item.getFrameworkRoots());
    }

    return NativeLinkableInput.of(
        inputs.build(),
        args.build(),
        frameworkRoots.build());
  }

  public static NativeLinkableInput of() {
    return EMPTY;
  }

}
