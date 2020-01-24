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

package com.facebook.buck.cxx.toolchain.nativelink;

import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Set;
import org.immutables.value.Value;

/**
 * A class containing inputs to be passed to the native linker. Dependencies (e.g. C++ libraries) of
 * a top-level native linkable rule (e.g. C++ binary) can use this to contribute components to the
 * final link.
 */
@BuckStyleValueWithBuilder
public abstract class NativeLinkableInput {

  private static final NativeLinkableInput INSTANCE =
      ImmutableNativeLinkableInput.builder().build();

  // Arguments to pass to the linker.  In the future it'd be nice to make this more aware of
  // the differences between archives, objects, flags, etc.
  @Value.Parameter
  public abstract ImmutableList<Arg> getArgs();

  // Frameworks that are used by the linkable to link with.
  @Value.Parameter
  public abstract ImmutableSet<FrameworkPath> getFrameworks();

  // Libraries to link.
  @Value.Parameter
  public abstract ImmutableSet<FrameworkPath> getLibraries();

  /** Combine, in order, several {@link NativeLinkableInput} objects into a single one. */
  public static NativeLinkableInput concat(Iterable<NativeLinkableInput> items) {
    ImmutableList.Builder<Arg> args = ImmutableList.builder();
    ImmutableSet.Builder<FrameworkPath> frameworks = ImmutableSet.builder();
    ImmutableSet.Builder<FrameworkPath> libraries = ImmutableSet.builder();

    for (NativeLinkableInput item : items) {
      args.addAll(item.getArgs());
      frameworks.addAll(item.getFrameworks());
      libraries.addAll(item.getLibraries());
    }

    return NativeLinkableInput.of(args.build(), frameworks.build(), libraries.build());
  }

  public NativeLinkableInput withArgs(List<Arg> args) {
    if (getArgs().equals(args)) {
      return this;
    }
    return builder().from(this).setArgs(args).build();
  }

  public static NativeLinkableInput of() {
    return INSTANCE;
  }

  public static NativeLinkableInput of(
      List<Arg> args, Set<FrameworkPath> frameworks, Set<FrameworkPath> libraries) {

    return builder().setArgs(args).setFrameworks(frameworks).setLibraries(libraries).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableNativeLinkableInput.Builder {
    @Override
    public NativeLinkableInput build() {
      NativeLinkableInput instance = super.build();
      if (instance.equals(INSTANCE)) {
        return INSTANCE;
      }
      return instance;
    }
  }
}
