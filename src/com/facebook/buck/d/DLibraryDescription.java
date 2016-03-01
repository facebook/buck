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

package com.facebook.buck.d;

import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.Archives;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
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
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class DLibraryDescription implements Description<DLibraryDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("d_library");

  private final DBuckConfig dBuckConfig;
  private final CxxPlatform cxxPlatform;

  public DLibraryDescription(
      DBuckConfig dBuckConfig,
      CxxPlatform cxxPlatform) {
    this.dBuckConfig = dBuckConfig;
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
      BuildRuleResolver buildRuleResolver,
      A args) {

    return createStaticLibraryBuildRule(
        params,
        buildRuleResolver,
        new SourcePathResolver(buildRuleResolver),
        dBuckConfig,
        cxxPlatform,
        args.srcs,
        /* compilerFlags */ ImmutableList.<String>of(),
        CxxSourceRuleFactory.PicType.PDC);
  }

  /**
   * @return a BuildRule that creates a static library.
   */
  private static BuildRule createStaticLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      DBuckConfig dBuckConfig,
      CxxPlatform cxxPlatform,
      Iterable<SourcePath> sources,
      ImmutableList<String> compilerFlags,
      CxxSourceRuleFactory.PicType pic) {

    ImmutableList<SourcePath> compiledSources =
      DDescriptionUtils.sourcePathsForCompiledSources(
          sources,
          compilerFlags,
          params,
          ruleResolver,
          pathResolver,
          cxxPlatform,
          dBuckConfig);

    // Write a build rule to create the archive for this library.
    BuildTarget staticTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor(),
            pic);

    Path staticLibraryPath =
        CxxDescriptionEnhancer.getStaticLibraryPath(
            params.getBuildTarget(),
            cxxPlatform.getFlavor(),
            pic);

    Archive archiveRule = Archives.createArchiveRule(
        pathResolver,
        staticTarget,
        params.copyWithBuildTarget(
            BuildTarget.builder().from(params.getBuildTarget())
            .addFlavors(
                cxxPlatform.getFlavor(),
                CxxDescriptionEnhancer.STATIC_FLAVOR)
            .build()),
        cxxPlatform.getAr(),
        cxxPlatform.getRanlib(),
        staticLibraryPath,
        compiledSources);
    ruleResolver.addToIndex(archiveRule);

    return new DLibrary(
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>of(archiveRule))),
        ruleResolver,
        pathResolver);
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public ImmutableSortedSet<SourcePath> srcs;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
