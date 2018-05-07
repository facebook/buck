/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class PrebuiltRustLibraryDescription
    implements Description<PrebuiltRustLibraryDescriptionArg>,
        VersionPropagator<PrebuiltRustLibraryDescriptionArg> {

  @Override
  public Class<PrebuiltRustLibraryDescriptionArg> getConstructorArgType() {
    return PrebuiltRustLibraryDescriptionArg.class;
  }

  @Override
  public PrebuiltRustLibrary createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PrebuiltRustLibraryDescriptionArg args) {
    CxxDeps allDeps =
        CxxDeps.builder().addDeps(args.getDeps()).addPlatformDeps(args.getPlatformDeps()).build();
    return new PrebuiltRustLibrary(buildTarget, context.getProjectFilesystem(), params) {

      @Override
      protected SourcePath getRlib() {
        return args.getRlib();
      }

      @Override
      public com.facebook.buck.rules.args.Arg getLinkerArg(
          boolean direct,
          boolean isCheck,
          RustPlatform rustPlatform,
          Linker.LinkableDepType depType) {
        return new RustLibraryArg(args.getCrate(), args.getRlib(), direct);
      }

      @Override
      public boolean isProcMacro() {
        return args.getProcMacro();
      }

      @Override
      public NativeLinkable.Linkage getPreferredLinkage() {
        return NativeLinkable.Linkage.STATIC;
      }

      @Override
      public ImmutableMap<String, SourcePath> getRustSharedLibraries(RustPlatform rustPlatform) {
        return ImmutableMap.of();
      }

      @Override
      public Iterable<BuildRule> getRustLinakbleDeps(RustPlatform rustPlatform) {
        return allDeps.get(context.getBuildRuleResolver(), rustPlatform.getCxxPlatform());
      }
    };
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPrebuiltRustLibraryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps {
    SourcePath getRlib();

    @Value.Default
    default String getCrate() {
      return getName();
    }

    Optional<Linker.LinkableDepType> getLinkStyle();

    @Value.Default
    default boolean getProcMacro() {
      return false;
    }

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }
  }
}
