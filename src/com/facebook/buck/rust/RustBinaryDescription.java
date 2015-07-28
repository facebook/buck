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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Set;

public class RustBinaryDescription implements Description<RustBinaryDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("rust_binary");

  private final RustBuckConfig rustBuckConfig;

  public RustBinaryDescription(RustBuckConfig rustBuckConfig) {
    this.rustBuckConfig = rustBuckConfig;
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
        ImmutableSet.copyOf(args.srcs),
        ImmutableSet.copyOf(args.features.get()),
        ImmutableList.copyOf(args.rustcFlags.get()),
        rustBuckConfig.getRustCompiler().get());
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public ImmutableSet<SourcePath> srcs;
    public Optional<Set<String>> features;
    public Optional<Set<String>> rustcFlags;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
