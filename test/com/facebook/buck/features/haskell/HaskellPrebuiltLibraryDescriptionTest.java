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

package com.facebook.buck.features.haskell;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.CxxHeadersDir;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.FakeSourcePath;
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
  public void staticLibraries() {
    PathSourcePath lib = FakeSourcePath.of("libfoo.a");
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(FakeSourcePath.of("package.conf.d"))
            .setStaticLibs(ImmutableList.of(lib));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput input =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, resolver);
    assertThat(
        RichStream.from(input.getArgs())
            .flatMap(
                a ->
                    BuildableSupport.deriveInputs(a)
                        .collect(ImmutableList.toImmutableList())
                        .stream())
            .toImmutableSet(),
        Matchers.contains(lib));
  }

  @Test
  public void sharedLibraries() {
    PathSourcePath lib = FakeSourcePath.of("libfoo.so");
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(FakeSourcePath.of("package.conf.d"))
            .setSharedLibs(ImmutableMap.of("libfoo.so", lib));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput input =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED, resolver);
    assertThat(
        RichStream.from(input.getArgs())
            .flatMap(
                a ->
                    BuildableSupport.deriveInputs(a)
                        .collect(ImmutableList.toImmutableList())
                        .stream())
            .toImmutableSet(),
        Matchers.contains(lib));
  }

  @Test
  public void interfaces() {
    PathSourcePath interfaces = FakeSourcePath.of("interfaces");
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(FakeSourcePath.of("package.conf.d"))
            .setImportDirs(ImmutableList.of(interfaces));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    HaskellCompileInput input =
        library.getCompileInput(
            HaskellTestUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, false);
    assertThat(input.getPackages().get(0).getInterfaces(), Matchers.contains(interfaces));
  }

  @Test
  public void packageDb() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PathSourcePath db = FakeSourcePath.of("package.conf.d");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target).setVersion("1.0.0").setDb(db);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    HaskellCompileInput input =
        library.getCompileInput(
            HaskellTestUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, false);
    assertThat(input.getPackages().get(0).getPackageDb(), Matchers.equalTo(db));
  }

  @Test
  public void packageInfo() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(FakeSourcePath.of("package.conf.d"))
            .setId("id");
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    HaskellCompileInput input =
        library.getCompileInput(
            HaskellTestUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, false);
    assertThat(
        input.getPackages().get(0).getInfo(),
        Matchers.equalTo(HaskellPackageInfo.of("rule", "1.0.0", "id")));
  }

  @Test
  public void exportedLinkerFlags() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    String flag = "-exported-linker-flags";
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(FakeSourcePath.of("package.conf.d"))
            .setExportedLinkerFlags(ImmutableList.of(flag));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput staticInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, resolver);
    assertThat(Arg.stringify(staticInput.getArgs(), pathResolver), Matchers.contains(flag));
    NativeLinkableInput sharedInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED, resolver);
    assertThat(Arg.stringify(sharedInput.getArgs(), pathResolver), Matchers.contains(flag));
  }

  @Test
  public void exportedCompilerFlags() {
    String flag = "-exported-compiler-flags";
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(FakeSourcePath.of("package.conf.d"))
            .setExportedCompilerFlags(ImmutableList.of(flag));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    HaskellCompileInput staticInput =
        library.getCompileInput(
            HaskellTestUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, false);
    assertThat(staticInput.getFlags(), Matchers.contains(flag));
    HaskellCompileInput sharedInput =
        library.getCompileInput(
            HaskellTestUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC_PIC, false);
    assertThat(sharedInput.getFlags(), Matchers.contains(flag));
  }

  @Test
  public void cxxHeaderDirs() {
    PathSourcePath interfaces = FakeSourcePath.of("interfaces");
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    PathSourcePath path = FakeSourcePath.of("include_dir");
    PrebuiltHaskellLibraryBuilder builder =
        new PrebuiltHaskellLibraryBuilder(target)
            .setVersion("1.0.0")
            .setDb(FakeSourcePath.of("package.conf.d"))
            .setImportDirs(ImmutableList.of(interfaces))
            .setCxxHeaderDirs(ImmutableSortedSet.of(path));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltHaskellLibrary library = builder.build(resolver, filesystem, targetGraph);
    assertThat(
        library.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, resolver),
        Matchers.equalTo(
            CxxPreprocessorInput.builder()
                .addIncludes(CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, path))
                .build()));
  }
}
