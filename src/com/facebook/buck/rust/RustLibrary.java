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

import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class RustLibrary extends RustCompile implements RustLinkable {
  public RustLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      String crate,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<String> features,
      ImmutableList<String> rustcFlags,
      Supplier<Tool> compiler,
      Supplier<Tool> linker,
      ImmutableList<String> linkerArgs,
      Linker.LinkableDepType linkStyle) {
    super(
        params,
        resolver,
        crate,
        srcs,
        ImmutableList.<String>builder()
            .add("--crate-type", "rlib")
            .add("--emit", "link")
            .addAll(rustcFlags)
            .build(),
        features,
        ImmutableSet.of(),
        BuildTargets.getGenPath(
            params.getProjectFilesystem(),
            params.getBuildTarget(),
            "%s/lib" + crate + ".rlib"),
        compiler,
        linker,
        linkerArgs,
        linkStyle);
  }

  @Override
  protected String getDefaultSource() {
    return "lib.rs";
  }

  @Override
  public String getLinkTarget() {
    return getCrateName();
  }

  @Override
  public Path getLinkPath() {
    return getPathToOutput();
  }

  @Override
  public ImmutableSortedSet<Path> getDependencyPaths() {
    return RustLinkables.getDependencyPaths(this);
  }

  @Override
  public BuildableProperties getProperties() {
    return new BuildableProperties(BuildableProperties.Kind.LIBRARY);
  }
}
