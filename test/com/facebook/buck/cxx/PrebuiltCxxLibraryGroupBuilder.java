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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.BuildRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.regex.Pattern;

public class PrebuiltCxxLibraryGroupBuilder
    extends AbstractNodeBuilder<
        PrebuiltCxxLibraryGroupDescriptionArg.Builder, PrebuiltCxxLibraryGroupDescriptionArg,
        PrebuiltCxxLibraryGroupDescription, BuildRule> {

  public PrebuiltCxxLibraryGroupBuilder(BuildTarget target) {
    super(PrebuiltCxxLibraryGroupDescription.of(), target);
  }

  public PrebuiltCxxLibraryGroupBuilder setExportedPreprocessorFlags(ImmutableList<String> flags) {
    getArgForPopulating().setExportedPreprocessorFlags(flags);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setIncludeDirs(ImmutableList<SourcePath> includeDirs) {
    getArgForPopulating().setIncludeDirs(includeDirs);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setStaticLink(ImmutableList<String> args) {
    getArgForPopulating().setStaticLink(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setStaticLibs(ImmutableList<SourcePath> args) {
    getArgForPopulating().setStaticLibs(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setStaticPicLink(ImmutableList<String> args) {
    getArgForPopulating().setStaticPicLink(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setStaticPicLibs(ImmutableList<SourcePath> args) {
    getArgForPopulating().setStaticPicLibs(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setSharedLink(ImmutableList<String> args) {
    getArgForPopulating().setSharedLink(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setSharedLibs(ImmutableMap<String, SourcePath> args) {
    getArgForPopulating().setSharedLibs(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setProvidedSharedLibs(
      ImmutableMap<String, SourcePath> args) {
    getArgForPopulating().setProvidedSharedLibs(args);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setExportedDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setExportedDeps(deps);
    return this;
  }

  public PrebuiltCxxLibraryGroupBuilder setSupportedPlatformsRegex(Pattern pattern) {
    getArgForPopulating().setSupportedPlatformsRegex(Optional.of(pattern));
    return this;
  }
}
