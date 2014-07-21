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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class CxxBuckConfig {

  private final BuckConfig delegate;

  public CxxBuckConfig(BuckConfig delegate) {
    this.delegate = Preconditions.checkNotNull(delegate);
  }

  private ImmutableList<String> maybeSplit(Optional<String> flags) {
    ImmutableList.Builder<String> split = ImmutableList.builder();
    if (flags.isPresent() && !flags.get().trim().isEmpty()) {
      split.addAll(Splitter.on(" ").split(flags.get().trim()));
    }
    return split.build();
  }

  private String getRequiredString(String section, String name) {
    Optional<String> value = delegate.getValue(section, name);
    if (!value.isPresent()) {
      throw new HumanReadableException(
          String.format("Required config setting %s:%s not set", section, name));
    }
    return value.get();
  }

  private Path getRequiredPath(String section, String name) {
    Optional<Path> path = delegate.getPath(section, name);
    if (!path.isPresent()) {
      throw new HumanReadableException(
          String.format("Required config setting %s:%s not set", section, name));
    }
    return path.get();
  }

  public Optional<Path> getCompiler() {
    return delegate.getPath("cxx", "compiler");
  }

  public Optional<Path> getAr() {
    return delegate.getPath("cxx", "ar");
  }

  public Optional<Path> getLd() {
    return delegate.getPath("cxx", "linker");
  }

  public ImmutableList<String> getCFlags() {
    return maybeSplit(delegate.getValue("cxx", "cflags"));
  }

  public ImmutableList<String> getCxxFlags() {
    return maybeSplit(delegate.getValue("cxx", "cxxflags"));
  }

  public ImmutableList<String> getCppFlags() {
    return maybeSplit(delegate.getValue("cxx", "cppflags"));
  }

  public ImmutableList<String> getCxxppFlags() {
    return maybeSplit(delegate.getValue("cxx", "cxxppflags"));
  }

  public ImmutableList<String> getLdFlags() {
    return maybeSplit(delegate.getValue("cxx", "ldflags"));
  }

  public ImmutableList<String> getCxxLdFlags() {
    return maybeSplit(delegate.getValue("cxx", "cxxldflags"));
  }

}
