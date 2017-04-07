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

package com.facebook.buck.rust;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.versions.VersionPropagator;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class PrebuiltRustLibraryDescription implements
    Description<PrebuiltRustLibraryDescription.Arg>,
    VersionPropagator<PrebuiltRustLibraryDescription.Arg> {

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> PrebuiltRustLibrary createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    final SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(resolver));

    return new PrebuiltRustLibrary(params, pathResolver) {

      @Override
      protected SourcePath getRlib() {
        return args.rlib;
      }

      @Override
      public com.facebook.buck.rules.args.Arg getLinkerArg(
          boolean direct,
          boolean isCheck,
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType depType) {
        return new RustLibraryArg(
            getResolver(),
            args.crate.orElse(getBuildTarget().getShortName()),
            args.rlib,
            direct,
            getBuildDeps());
      }

      @Override
      public NativeLinkable.Linkage getPreferredLinkage() {
        return NativeLinkable.Linkage.STATIC;
      }

      @Override
      public ImmutableMap<String, SourcePath> getRustSharedLibraries(CxxPlatform cxxPlatform) {
        return ImmutableMap.of();
      }
    };
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public SourcePath rlib;
    public Optional<String> crate;
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public Optional<Linker.LinkableDepType> linkStyle;
  }
}
