/*
 * Copyright 2016-present Facebook, Inc.
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

public class RustLibraryBuilder extends
    AbstractNodeBuilder<RustLibraryDescription.Arg, RustLibraryDescription> {

  public RustLibraryBuilder(
      RustLibraryDescription description,
      BuildTarget target) {
    super(description, target);
  }

  public RustLibraryBuilder(
      BuildTarget target,
      RustBuckConfig config,
      CxxPlatform defaultCxxPlatform) {
    this(new RustLibraryDescription(config, defaultCxxPlatform), target);
  }

  public RustLibraryBuilder(BuildTarget target, RustBuckConfig config) {
    this(
        target,
        config,
        CxxPlatformUtils.DEFAULT_PLATFORM);
  }

  public RustLibraryBuilder(BuildTarget target) {
    this(target, new FakeRustConfig());
  }

  public RustLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = deps;
    return this;
  }

  public RustLibraryBuilder setSrcs(ImmutableSortedSet<SourcePath> srcs) {
    arg.srcs = srcs;
    return this;
  }

  public RustLibraryBuilder setLinkStyle(Optional<Linker.LinkableDepType> linkStyle) {
    arg.linkStyle = linkStyle;
    return this;
  }
}

