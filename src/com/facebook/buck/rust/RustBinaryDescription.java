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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.util.List;
import java.util.Optional;

public class RustBinaryDescription implements Description<RustBinaryDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("rust_binary");

  private final RustBuckConfig rustBuckConfig;
  private final CxxPlatform cxxPlatform;

  public RustBinaryDescription(RustBuckConfig rustBuckConfig, CxxPlatform cxxPlatform) {
    this.rustBuckConfig = rustBuckConfig;
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return new RustBinary(
        params,
        new SourcePathResolver(resolver),
        args.crate.orElse(params.getBuildTarget().getShortName()),
        ImmutableSortedSet.copyOf(args.srcs),
        ImmutableSortedSet.copyOf(args.features),
        ImmutableList.copyOf(args.rustcFlags),
        rustBuckConfig.getRustCompiler().get(),
        cxxPlatform,
        args.linkStyle.orElse(Linker.LinkableDepType.STATIC));
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableSortedSet<SourcePath> srcs;
    public ImmutableSortedSet<String> features = ImmutableSortedSet.of();
    public List<String> rustcFlags = ImmutableList.of();
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public Optional<Linker.LinkableDepType> linkStyle;
    public Optional<String> crate;
  }
}
