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

package com.facebook.buck.go;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.List;

public class GoLibraryDescription implements Description<GoLibraryDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("go_library");

  private final GoBuckConfig goBuckConfig;

  public GoLibraryDescription(GoBuckConfig goBuckConfig) {
    this.goBuckConfig = goBuckConfig;
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
    return GoDescriptors.createGoLibraryRule(
        params,
        resolver,
        goBuckConfig,
        args.packageName.or(params.getBuildTarget().getBasePath()),
        args.srcs,
        args.compilerFlags.or(ImmutableList.<String>of())
    );
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public ImmutableSet<SourcePath> srcs;
    public Optional<List<String>> compilerFlags;
    public Optional<Path> packageName;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
