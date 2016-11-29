/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rust;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class RustBinaryBuilder extends
    AbstractNodeBuilder<RustBinaryDescription.Arg, RustBinaryDescription> {

  public RustBinaryBuilder(
      RustBinaryDescription description,
      BuildTarget target) {
    super(description, target);
  }

  public RustBinaryBuilder(
      BuildTarget target,
      RustBuckConfig config,
      CxxPlatform defaultCxxPlatform) {
    this(new RustBinaryDescription(config, defaultCxxPlatform), target);
  }

  public RustBinaryBuilder(BuildTarget target, RustBuckConfig config) {
    this(
        target,
        config,
        CxxPlatformUtils.DEFAULT_PLATFORM);
  }

  public RustBinaryBuilder(BuildTarget target) {
    this(target, new FakeRustConfig());
  }

  public RustBinaryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = deps;
    return this;
  }

  public RustBinaryBuilder setSrcs(ImmutableSortedSet<SourcePath> srcs) {
    arg.srcs = srcs;
    return this;
  }

  public RustBinaryBuilder setLinkStyle(Optional<Linker.LinkableDepType> linkStyle) {
    arg.linkStyle = linkStyle;
    return this;
  }
}

