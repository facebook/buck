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

package com.facebook.buck.dotnet;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

public class CSharpLibraryDescription implements Description<CSharpLibraryDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("csharp_library");

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

    ImmutableList.Builder<Either<BuildRule, String>> refsAsRules = ImmutableList.builder();
    for (Either<BuildTarget, String> ref : args.deps.get()) {
      if (ref.isLeft()) {
        refsAsRules.add(Either.<BuildRule, String>ofLeft(resolver.getRule(ref.getLeft())));
      } else {
        refsAsRules.add(Either.<BuildRule, String>ofRight(ref.getRight()));
      }
    }

    String suggestedOut = args.dllName.or(params.getBuildTarget().getShortName() + ".dll");

    return new CSharpLibrary(
        params,
        new SourcePathResolver(resolver),
        suggestedOut,
        args.srcs,
        refsAsRules.build(),
        args.resources.get(),
        args.frameworkVer);
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public FrameworkVersion frameworkVer;
    public ImmutableSortedSet<SourcePath> srcs;
    public Optional<ImmutableMap<String, SourcePath>> resources;
    public Optional<String> dllName;

    // We may have system-provided references ("System.Core.dll") or other build targets
    public Optional<ImmutableList<Either<BuildTarget, String>>> deps;
  }
}
