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
import com.facebook.buck.model.FlavorDomain;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

public class PrebuiltCxxLibraryBuilder
    extends AbstractCxxBuilder<PrebuiltCxxLibraryDescription.Arg> {

  public PrebuiltCxxLibraryBuilder(
      BuildTarget target,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(new PrebuiltCxxLibraryDescription(cxxPlatforms), target);
  }

  public PrebuiltCxxLibraryBuilder(BuildTarget target) {
    this(target, createDefaultPlatforms());
  }

  public PrebuiltCxxLibraryBuilder setIncludeDirs(ImmutableList<String> includeDirs) {
    arg.includeDirs = Optional.of(includeDirs);
    return this;
  }

  public PrebuiltCxxLibraryBuilder setLibName(String libName) {
    arg.libName = Optional.of(libName);
    return this;
  }

  public PrebuiltCxxLibraryBuilder setLibDir(String libDir) {
    arg.libDir = Optional.of(libDir);
    return this;
  }

  public PrebuiltCxxLibraryBuilder setHeaderOnly(boolean headerOnly) {
    arg.headerOnly = Optional.of(headerOnly);
    return this;
  }

  public PrebuiltCxxLibraryBuilder setProvided(boolean provided) {
    arg.provided = Optional.of(provided);
    return this;
  }

  public PrebuiltCxxLibraryBuilder setLinkerFlags(ImmutableList<String> linkerFlags) {
    arg.exportedLinkerFlags = Optional.of(linkerFlags);
    return this;
  }

  public PrebuiltCxxLibraryBuilder setLinkWhole(boolean linkWhole) {
    arg.linkWhole = Optional.of(linkWhole);
    return this;
  }

  public PrebuiltCxxLibraryBuilder setSoname(String soname) {
    arg.soname = Optional.of(soname);
    return this;
  }

  public PrebuiltCxxLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = Optional.of(deps);
    return this;
  }

}
