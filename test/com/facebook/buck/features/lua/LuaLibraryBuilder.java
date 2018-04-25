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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class LuaLibraryBuilder
    extends AbstractNodeBuilder<
        LuaLibraryDescriptionArg.Builder, LuaLibraryDescriptionArg, LuaLibraryDescription,
        LuaLibrary> {

  public LuaLibraryBuilder(LuaLibraryDescription description, BuildTarget target) {
    super(description, target);
  }

  public LuaLibraryBuilder(BuildTarget target) {
    this(new LuaLibraryDescription(), target);
  }

  public LuaLibraryBuilder setBaseModule(String baseModule) {
    getArgForPopulating().setBaseModule(Optional.of(baseModule));
    return this;
  }

  public LuaLibraryBuilder setSrcs(ImmutableSortedSet<SourcePath> srcs) {
    getArgForPopulating().setSrcs(SourceList.ofUnnamedSources(srcs));
    return this;
  }

  public LuaLibraryBuilder setSrcs(ImmutableSortedMap<String, SourcePath> srcs) {
    getArgForPopulating().setSrcs(SourceList.ofNamedSources(srcs));
    return this;
  }

  public LuaLibraryBuilder setPlatformDeps(
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> deps) {
    getArgForPopulating().setPlatformDeps(deps);
    return this;
  }
}
