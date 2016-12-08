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

package com.facebook.buck.lua;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.versions.VersionPropagator;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class LuaLibraryDescription implements
    Description<LuaLibraryDescription.Arg>,
    VersionPropagator<LuaLibraryDescription.Arg> {

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      final A args) {
    final SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return new LuaLibrary(params, pathResolver) {
      @Override
      public LuaPackageComponents getLuaPackageComponents() {
        return LuaPackageComponents.builder()
            .putAllModules(
                LuaUtil.toModuleMap(
                    params.getBuildTarget(),
                    pathResolver,
                    "srcs",
                    LuaUtil.getBaseModule(params.getBuildTarget(), args.baseModule),
                    ImmutableList.of(
                        args.srcs)))
            .build();
      }
    };
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public SourceList srcs = SourceList.EMPTY;
    public Optional<String> baseModule;
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
  }

}
