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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class HaskellPrebuiltLibraryDescriptionTest {

  @Test
  public void staticLibraries() throws Exception {
    PathSourcePath lib = new FakeSourcePath("libfoo.a");
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setStaticLibs(ImmutableList.of(lib));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput input =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC);
    assertThat(
        RichStream.from(input.getArgs()).flatMap(a -> a.getInputs().stream()).toImmutableSet(),
        Matchers.contains(lib));
  }

  @Test
  public void sharedLibraries() throws Exception {
    PathSourcePath lib = new FakeSourcePath("libfoo.so");
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setSharedLibs(ImmutableMap.of("libfoo.so", lib));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput input =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED);
    assertThat(
        RichStream.from(input.getArgs()).flatMap(a -> a.getInputs().stream()).toImmutableSet(),
        Matchers.contains(lib));
  }

  @Test
  public void interfaces() throws Exception {
    PathSourcePath interfaces = new FakeSourcePath("interfaces");
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setImportDirs(ImmutableList.of(interfaces));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    HaskellCompileInput input =
        library.getCompileInput(CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC);
    assertThat(input.getPackages().get(0).getInterfaces(), Matchers.contains(interfaces));
  }

  @Test
  public void packageDb() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PathSourcePath db = new FakeSourcePath("package.conf.d");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target).setVersion("1.0.0").setDb(db);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    HaskellCompileInput input =
        library.getCompileInput(CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC);
    assertThat(input.getPackages().get(0).getPackageDb(), Matchers.equalTo(db));
  }

  @Test
  public void packageInfo() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setId("id");
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    HaskellCompileInput input =
        library.getCompileInput(CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC);
    assertThat(
        input.getPackages().get(0).getInfo(),
        Matchers.equalTo(HaskellPackageInfo.of("rule", "1.0.0", "id")));
  }

  @Test
  public void exportedLinkerFlags() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    String flag = "-exported-linker-flags";
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setExportedLinkerFlags(ImmutableList.of(flag));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput staticInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC);
    assertThat(Arg.stringify(staticInput.getArgs(), pathResolver), Matchers.contains(flag));
    NativeLinkableInput sharedInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED);
    assertThat(Arg.stringify(sharedInput.getArgs(), pathResolver), Matchers.contains(flag));
  }

  @Test
  public void exportedCompilerFlags() throws Exception {
    String flag = "-exported-compiler-flags";
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setExportedCompilerFlags(ImmutableList.of(flag));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    HaskellCompileInput staticInput =
        library.getCompileInput(CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC);
    assertThat(staticInput.getFlags(), Matchers.contains(flag));
    HaskellCompileInput sharedInput =
        library.getCompileInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC_PIC);
    assertThat(sharedInput.getFlags(), Matchers.contains(flag));
  }

  @Test
  public void cxxHeaderDirs() throws Exception {
    PathSourcePath interfaces = new FakeSourcePath("interfaces");
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PathSourcePath path = new FakeSourcePath("include_dir");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(new FakeSourcePath("package.conf.d"))
            .setImportDirs(ImmutableList.of(interfaces))
            .setCxxHeaderDirs(ImmutableSortedSet.of(path));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    assertThat(
        library.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, HeaderVisibility.PUBLIC),
        Matchers.equalTo(
            CxxPreprocessorInput.builder()
                .addIncludes(CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, path))
                .build()));
  }
}
