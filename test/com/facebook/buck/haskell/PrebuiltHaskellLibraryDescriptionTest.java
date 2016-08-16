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

package com.facebook.buck.haskell;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.CxxHeadersDir;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

public class PrebuiltHaskellLibraryDescriptionTest {

  @Test
  public void staticLibraries() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePath lib = new FakeSourcePath("libfoo.a");
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibrary library =
        (PrebuiltHaskellLibrary) new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setStaticLibs(ImmutableList.of(lib))
            .build(resolver);
    NativeLinkableInput input =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC);
    assertThat(
        FluentIterable.from(input.getArgs())
            .transformAndConcat(Arg.getInputsFunction())
            .toSet(),
        Matchers.contains(lib));
  }

  @Test
  public void sharedLibraries() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePath lib = new FakeSourcePath("libfoo.so");
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibrary library =
        (PrebuiltHaskellLibrary) new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setSharedLibs(ImmutableMap.of("libfoo.so", lib))
            .build(resolver);
    NativeLinkableInput input =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED);
    assertThat(
        FluentIterable.from(input.getArgs())
            .transformAndConcat(Arg.getInputsFunction())
            .toSet(),
        Matchers.contains(lib));
  }

  @Test
  public void interfaces() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePath interfaces = new FakeSourcePath("interfaces");
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibrary library =
        (PrebuiltHaskellLibrary) new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setImportDirs(ImmutableList.of(interfaces))
            .build(resolver);
    HaskellCompileInput input =
        library.getCompileInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC);
    assertThat(
        input.getPackages().get(0).getInterfaces(),
        Matchers.contains(interfaces));
  }

  @Test
  public void packageDb() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    SourcePath db = new FakeSourcePath("package.conf.d");
    PrebuiltHaskellLibrary library =
        (PrebuiltHaskellLibrary) new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(db)
            .build(resolver);
    HaskellCompileInput input =
        library.getCompileInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC);
    assertThat(
        input.getPackages().get(0).getPackageDb(),
        Matchers.equalTo(db));
  }

  @Test
  public void packageInfo() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibrary library =
        (PrebuiltHaskellLibrary) new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setId("id")
            .build(resolver);
    HaskellCompileInput input =
        library.getCompileInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC);
    assertThat(
        input.getPackages().get(0).getInfo(),
        Matchers.equalTo(
            HaskellPackageInfo.of("rule", "1.0.0", "id")));
  }

  @Test
  public void exportedLinkerFlags() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    String flag = "-exported-linker-flags";
    PrebuiltHaskellLibrary library =
        (PrebuiltHaskellLibrary) new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setExportedLinkerFlags(ImmutableList.of(flag))
            .build(resolver);
    NativeLinkableInput staticInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC);
    assertThat(
        Arg.stringify(staticInput.getArgs()),
        Matchers.contains(flag));
    NativeLinkableInput sharedInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED);
    assertThat(
        Arg.stringify(sharedInput.getArgs()),
        Matchers.contains(flag));
  }

  @Test
  public void exportedCompilerFlags() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    String flag = "-exported-compiler-flags";
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibrary library =
        (PrebuiltHaskellLibrary) new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setExportedCompilerFlags(ImmutableList.of(flag))
            .build(resolver);
    HaskellCompileInput staticInput =
        library.getCompileInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC);
    assertThat(
        staticInput.getFlags(),
        Matchers.contains(flag));
    HaskellCompileInput sharedInput =
        library.getCompileInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC);
    assertThat(
        sharedInput.getFlags(),
        Matchers.contains(flag));
  }

  @Test
  public void cxxHeaderDirs() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePath interfaces = new FakeSourcePath("interfaces");
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PathSourcePath path = new FakeSourcePath("include_dir");
    PrebuiltHaskellLibrary library =
        (PrebuiltHaskellLibrary) new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setImportDirs(ImmutableList.of(interfaces))
            .setCxxHeaderDirs(ImmutableSortedSet.<SourcePath>of(path))
            .build(resolver);
    assertThat(
        library.getCxxPreprocessorInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            HeaderVisibility.PUBLIC),
        Matchers.equalTo(
            CxxPreprocessorInput.builder()
                .addIncludes(
                    CxxHeadersDir.of(
                        CxxPreprocessables.IncludeType.SYSTEM,
                        path))
                .build()));
  }

}
