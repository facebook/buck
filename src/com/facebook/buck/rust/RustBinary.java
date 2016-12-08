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
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;


public class RustBinary extends RustCompile implements BinaryBuildRule {
  public RustBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      String crate,
      Optional<SourcePath> crateRoot,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<String> features,
      ImmutableList<String> rustcFlags,
      Supplier<Tool> compiler,
      Supplier<Tool> linker,
      ImmutableList<String> linkerArgs,
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType linkStyle) throws NoSuchBuildTargetException {
    super(
        RustLinkables.addNativeDependencies(params, resolver, cxxPlatform, linkStyle),
        resolver,
        crate,
        crateRoot,
        srcs,
        ImmutableList.<String>builder()
            .add("--crate-type", "bin")
            .addAll(rustcFlags)
            .build(),
        features,
        RustLinkables.getNativeDirs(params.getDeps(), linkStyle, cxxPlatform),
        BuildTargets.getGenPath(
            params.getProjectFilesystem(),
            params.getBuildTarget(),
            "%s/" + crate),
        compiler,
        linker,
        extendLinkerArgs(
            linkerArgs,
            params.getDeps(),
            linkStyle,
            cxxPlatform),
        linkStyle);
  }

  private static ImmutableList<String> extendLinkerArgs(
      ImmutableList<String> linkerArgs,
      Iterable<BuildRule> deps,
      Linker.LinkableDepType linkStyle,
      CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    builder.addAll(linkerArgs);
    RustLinkables.accumNativeArgs(deps, linkStyle, cxxPlatform, builder);

    return builder.build();
  }

  @Override
  protected ImmutableSet<String> getDefaultSources() {
    return ImmutableSet.of("main.rs");
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder()
        .addArg(new SourcePathArg(getResolver(), new BuildTargetSourcePath(getBuildTarget())))
        .build();
  }

  @Override
  public BuildableProperties getProperties() {
    return new BuildableProperties(BuildableProperties.Kind.PACKAGING);
  }

}
