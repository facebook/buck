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

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class LuaLibraryDescription
    implements Description<LuaLibraryDescriptionArg>, VersionPropagator<LuaLibraryDescriptionArg> {

  @Override
  public Class<LuaLibraryDescriptionArg> getConstructorArgType() {
    return LuaLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final LuaLibraryDescriptionArg args) {
    final SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    return new LuaLibrary(buildTarget, projectFilesystem, params) {

      @Override
      public Iterable<BuildRule> getLuaPackageDeps(CxxPlatform cxxPlatform) {
        return resolver.getAllRules(
            LuaUtil.getDeps(cxxPlatform, args.getDeps(), args.getPlatformDeps()));
      }

      @Override
      public LuaPackageComponents getLuaPackageComponents() {
        return LuaPackageComponents.builder()
            .putAllModules(
                LuaUtil.toModuleMap(
                    buildTarget,
                    pathResolver,
                    "srcs",
                    LuaUtil.getBaseModule(buildTarget, args.getBaseModule()),
                    ImmutableList.of(args.getSrcs())))
            .build();
      }
    };
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractLuaLibraryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {

    @Value.Default
    default SourceList getSrcs() {
      return SourceList.EMPTY;
    }

    Optional<String> getBaseModule();

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }
  }
}
