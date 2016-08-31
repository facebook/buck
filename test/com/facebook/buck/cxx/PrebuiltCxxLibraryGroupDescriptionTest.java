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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;

import org.hamcrest.Matchers;
import org.junit.Test;

public class PrebuiltCxxLibraryGroupDescriptionTest {

  @Test
  public void exportedPreprocessorFlags() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    CxxPreprocessorDep lib =
        (CxxPreprocessorDep) new PrebuiltCxxLibraryGroupBuilder(target)
            .setExportedPreprocessorFlags(ImmutableList.of("-flag"))
            .build(resolver);
    assertThat(
        lib.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, HeaderVisibility.PUBLIC)
            .getPreprocessorFlags(),
        Matchers.<ImmutableMultimap<CxxSource.Type, String>>equalTo(
            CxxFlags.getLanguageFlags(
                Optional.of(ImmutableList.of("-flag")),
                Optional.<PatternMatchedCollection<ImmutableList<String>>>absent(),
                Optional.<ImmutableMap<AbstractCxxSource.Type, ImmutableList<String>>>absent(),
                CxxPlatformUtils.DEFAULT_PLATFORM)));
  }

  @Test
  public void includeDirs() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    SourcePath includes = new FakeSourcePath("include");
    CxxPreprocessorDep lib =
        (CxxPreprocessorDep) new PrebuiltCxxLibraryGroupBuilder(target)
            .setIncludeDirs(ImmutableList.of(includes))
            .build(resolver);
    assertThat(
        lib.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, HeaderVisibility.PUBLIC)
            .getIncludes(),
        Matchers.equalTo(
            ImmutableList.<CxxHeaders>of(
                CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, includes))));
  }

  @Test
  public void staticLink() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    SourcePath path = new FakeSourcePath("include");
    NativeLinkable lib =
        (NativeLinkable) new PrebuiltCxxLibraryGroupBuilder(target)
            .setStaticLink(ImmutableList.of("--something", "$(lib 0)", "--something-else"))
            .setStaticLibs(ImmutableList.of(path))
            .build(resolver);
    assertThat(
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC),
        Matchers.equalTo(
            NativeLinkableInput.builder()
                .addArgs(
                    new StringArg("--something"),
                    new SourcePathArg(pathResolver, path),
                    new StringArg("--something-else"))
                .build()));
  }

  @Test
  public void staticPicLink() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    SourcePath path = new FakeSourcePath("include");
    NativeLinkable lib =
        (NativeLinkable) new PrebuiltCxxLibraryGroupBuilder(target)
            .setStaticPicLink(ImmutableList.of("--something", "$(lib 0)", "--something-else"))
            .setStaticPicLibs(ImmutableList.of(path))
            .build(resolver);
    assertThat(
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC),
        Matchers.equalTo(
            NativeLinkableInput.builder()
                .addArgs(
                    new StringArg("--something"),
                    new SourcePathArg(pathResolver, path),
                    new StringArg("--something-else"))
                .build()));
  }


  @Test
  public void sharedLink() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    SourcePath lib1 = new FakeSourcePath("dir/lib1.so");
    PathSourcePath lib2 = new FakeSourcePath("dir/lib2.so");
    NativeLinkable lib =
        (NativeLinkable) new PrebuiltCxxLibraryGroupBuilder(target)
            .setSharedLink(
                ImmutableList.of(
                    "--something",
                    "$(lib lib1.so)",
                    "--something-else",
                    "$(rel-lib lib2.so)"))
            .setSharedLibs(ImmutableMap.of("lib1.so", lib1, "lib2.so", lib2))
            .build(resolver);
    assertThat(
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED),
        Matchers.equalTo(
            NativeLinkableInput.builder()
                .addArgs(
                    new StringArg("--something"),
                    new SourcePathArg(pathResolver, lib1),
                    new StringArg("--something-else"),
                    new RelativeLinkArg(lib2))
                .build()));
  }

}
