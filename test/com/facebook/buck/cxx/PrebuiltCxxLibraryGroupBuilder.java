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
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

public class PrebuiltCxxLibraryGroupBuilder
    extends AbstractNodeBuilder<PrebuiltCxxLibraryGroupDescription.Args> {

  public PrebuiltCxxLibraryGroupBuilder(BuildTarget target) {
    super(PrebuiltCxxLibraryGroupDescription.of(), target);
  }

  public PrebuiltCxxLibraryGroupBuilder setExportedPreprocessorFlags(ImmutableList<String> flags) {
    arg.exportedPreprocessorFlags = Optional.of(flags);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setIncludeDirs(ImmutableList<SourcePath> includeDirs) {
    arg.includeDirs = Optional.of(includeDirs);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setStaticLink(ImmutableList<String> args) {
    arg.staticLink = Optional.of(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setStaticLibs(ImmutableList<SourcePath> args) {
    arg.staticLibs = Optional.of(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setStaticPicLink(ImmutableList<String> args) {
    arg.staticPicLink = Optional.of(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setStaticPicLibs(ImmutableList<SourcePath> args) {
    arg.staticPicLibs = Optional.of(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setSharedLink(ImmutableList<String> args) {
    arg.sharedLink = Optional.of(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setSharedLibs(ImmutableMap<String, SourcePath> args) {
    arg.sharedLibs = Optional.of(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = Optional.of(deps);
    return this;
  }

}
