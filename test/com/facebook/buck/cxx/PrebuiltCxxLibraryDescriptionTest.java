/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.versions.Version;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PrebuiltCxxLibraryDescriptionTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:target");
  private static final BuildTarget TARGET_TWO = BuildTargetFactory.newInstance("//two/:target");
  private static final CxxPlatform CXX_PLATFORM = CxxPlatformUtils.DEFAULT_PLATFORM;

  private static Path getStaticLibraryPath(PrebuiltCxxLibraryDescriptionArg arg) {
    String libDir = arg.getLibDir().orElse("lib");
    String libName = arg.getLibName().orElse(TARGET.getShortName());
    return TARGET.getBasePath().resolve(libDir).resolve(String.format("lib%s.a", libName));
  }

  private static Path getStaticPicLibraryPath(PrebuiltCxxLibraryDescriptionArg arg) {
    String libDir = arg.getLibDir().orElse("lib");
    String libName = arg.getLibName().orElse(TARGET.getShortName());
    return TARGET.getBasePath().resolve(libDir).resolve(String.format("lib%s_pic.a", libName));
  }

  private static Path getSharedLibraryPath(PrebuiltCxxLibraryDescriptionArg arg) {
    String libDir = arg.getLibDir().orElse("lib");
    String libName = arg.getLibName().orElse(TARGET.getShortName());
    return TARGET
        .getBasePath()
        .resolve(libDir)
        .resolve(String.format("lib%s.%s", libName, CXX_PLATFORM.getSharedLibraryExtension()));
  }

  private static String getSharedLibrarySoname(PrebuiltCxxLibraryDescriptionArg arg) {
    String libName = arg.getLibName().orElse(TARGET.getShortName());
    return arg.getSoname()
        .orElse(String.format("lib%s.%s", libName, CXX_PLATFORM.getSharedLibraryExtension()));
  }

  private static ImmutableSet<BuildTarget> getInputRules(BuildRule buildRule) {
    return ImmutableSet.of(
        BuildTarget.builder()
            .from(buildRule.getBuildTarget())
            .addFlavors(CXX_PLATFORM.getFlavor())
            .addFlavors(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
            .build());
  }

  private static ImmutableSet<Path> getHeaderNames(Iterable<CxxHeaders> includes) {
    ImmutableSet.Builder<Path> names = ImmutableSet.builder();
    for (CxxHeaders headers : includes) {
      CxxSymlinkTreeHeaders symlinkTreeHeaders = (CxxSymlinkTreeHeaders) headers;
      names.addAll(symlinkTreeHeaders.getNameToPathMap().keySet());
    }
    return names.build();
  }

  @Test
  public void createBuildRuleDefault() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescriptionArg arg = libBuilder.build().getConstructorArg();

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput =
        NativeLinkableInput.of(
            ImmutableList.of(
                FileListableLinkerInputArg.withSourcePathArg(
                    SourcePathArg.of(new PathSourcePath(filesystem, getStaticLibraryPath(arg))))),
            ImmutableSet.of(),
            ImmutableSet.of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput =
        NativeLinkableInput.of(
            ImmutableList.of(
                SourcePathArg.of(new PathSourcePath(filesystem, getSharedLibraryPath(arg)))),
            ImmutableSet.of(),
            ImmutableSet.of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));
  }

  @Test
  public void headerOnly() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET).setHeaderOnly(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput =
        NativeLinkableInput.of(ImmutableList.of(), ImmutableSet.of(), ImmutableSet.of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput =
        NativeLinkableInput.of(ImmutableList.of(), ImmutableSet.of(), ImmutableSet.of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));
  }

  @Test
  public void createBuildRuleExternal() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET).setProvided(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescriptionArg arg = libBuilder.build().getConstructorArg();

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput =
        NativeLinkableInput.of(
            ImmutableList.of(
                SourcePathArg.of(new PathSourcePath(filesystem, getSharedLibraryPath(arg)))),
            ImmutableSet.of(),
            ImmutableSet.of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));
  }

  @Test
  public void missingSharedLibsAreAutoBuilt() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED);
    BuildRule rule =
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> arg.getDeps(ruleFinder))
            .toList()
            .get(0);
    assertTrue(rule instanceof CxxLink);
  }

  @Test
  public void missingSharedLibsAreNotAutoBuiltForHeaderOnlyRules() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET).setHeaderOnly(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> arg.getDeps(ruleFinder))
            .toList(),
        empty());
  }

  @Test
  public void addsLibsToAndroidPackageableCollector() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescriptionArg arg = libBuilder.build().getConstructorArg();
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            getSharedLibrarySoname(arg), new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        lib.getSharedLibraries(CXX_PLATFORM));
  }

  @Test
  public void locationMacro() throws NoSuchBuildTargetException {

    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

    CellPathResolver cellRoots = TestCellBuilder.createCellRoots(filesystem);
    Optional<String> libName = Optional.of("test");
    Optional<String> libDir = Optional.of("$(location //other:gen_lib)/");

    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    BuildTarget genTarget = BuildTargetFactory.newInstance("//other:gen_lib");
    GenruleBuilder genruleBuilder = GenruleBuilder.newGenruleBuilder(genTarget).setOut("lib_dir");

    BuildRule genRule = genruleBuilder.build(resolver);

    CxxPlatform platform =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("PLATFORM1"));

    Path path =
        pathResolver.getAbsolutePath(Preconditions.checkNotNull(genRule.getSourcePathToOutput()));
    final SourcePath staticLibraryPath =
        PrebuiltCxxLibraryDescription.getStaticLibraryPath(
            TARGET, cellRoots, filesystem, resolver, platform, Optional.empty(), libDir, libName);
    assertEquals(
        TARGET.getBasePath().resolve(String.format("%s/libtest.a", path)),
        pathResolver.getAbsolutePath(staticLibraryPath));
  }

  @Test
  public void goodPathNoLocation() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    CxxPlatform platform =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("PLATFORM1"));

    final SourcePath staticLibraryPath =
        PrebuiltCxxLibraryDescription.getStaticLibraryPath(
            TARGET_TWO,
            TestCellBuilder.createCellRoots(filesystem),
            filesystem,
            resolver,
            platform,
            Optional.empty(),
            Optional.of("lib"),
            Optional.empty());

    assertThat(
        MorePaths.pathWithUnixSeparators(pathResolver.getAbsolutePath(staticLibraryPath)),
        Matchers.containsString(String.format("two/%s/libtarget.a", "lib")));
  }

  @Test
  public void findDepsFromParamsWithLocation() throws NoSuchBuildTargetException {
    BuildTarget genTarget = BuildTargetFactory.newInstance("//other:gen_lib");
    GenruleBuilder genruleBuilder = GenruleBuilder.newGenruleBuilder(genTarget).setOut("lib_dir");

    PrebuiltCxxLibraryBuilder builder = new PrebuiltCxxLibraryBuilder(TARGET);
    builder.setSoname("test");
    builder.setLibDir("$(location //other:gen_lib)");

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(genruleBuilder.build(), builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule genrule = genruleBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) builder.build(resolver, filesystem, targetGraph);

    ImmutableSortedSet<BuildTarget> implicit = builder.findImplicitDeps();
    assertEquals(ImmutableSortedSet.of(genTarget), implicit);

    assertThat(lib.getBuildDeps(), Matchers.contains(genrule));
  }

  @Test
  public void findDepsFromParamsWithNone() throws NoSuchBuildTargetException {
    PrebuiltCxxLibraryBuilder builder = new PrebuiltCxxLibraryBuilder(TARGET);
    builder.setSoname("test");
    builder.setLibDir("lib");
    assertThat(builder.findImplicitDeps(), empty());
  }

  @Test
  public void findDepsFromParamsWithPlatform() throws NoSuchBuildTargetException {
    PrebuiltCxxLibraryBuilder builder = new PrebuiltCxxLibraryBuilder(TARGET);
    builder.setSoname("test");
    builder.setLibDir("$(platform)");
    assertThat(builder.findImplicitDeps(), empty());
  }

  @Test
  public void platformMacro() {
    Optional<String> libDir = Optional.of("libs/$(platform)");
    Optional<String> libName = Optional.of("test-$(platform)");

    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

    CellPathResolver cellRoots = TestCellBuilder.createCellRoots(filesystem);

    CxxPlatform platform1 =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("PLATFORM1"));
    CxxPlatform platform2 =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("PLATFORM2"));

    assertEquals(
        filesystem.resolve(
            TARGET
                .getBasePath()
                .resolve(
                    String.format(
                        "libs/PLATFORM1/libtest-PLATFORM1.%s",
                        platform1.getSharedLibraryExtension()))),
        pathResolver.getAbsolutePath(
            PrebuiltCxxLibraryDescription.getSharedLibraryPath(
                TARGET,
                cellRoots,
                filesystem,
                resolver,
                platform1,
                Optional.empty(),
                libDir,
                libName)));
    assertEquals(
        filesystem.resolve(TARGET.getBasePath().resolve("libs/PLATFORM1/libtest-PLATFORM1.a")),
        pathResolver.getAbsolutePath(
            PrebuiltCxxLibraryDescription.getStaticLibraryPath(
                TARGET,
                cellRoots,
                filesystem,
                resolver,
                platform1,
                Optional.empty(),
                libDir,
                libName)));

    assertEquals(
        filesystem.resolve(
            TARGET
                .getBasePath()
                .resolve(
                    String.format(
                        "libs/PLATFORM2/libtest-PLATFORM2.%s",
                        platform2.getSharedLibraryExtension()))),
        pathResolver.getAbsolutePath(
            PrebuiltCxxLibraryDescription.getSharedLibraryPath(
                TARGET,
                cellRoots,
                filesystem,
                resolver,
                platform2,
                Optional.empty(),
                libDir,
                libName)));
    assertEquals(
        filesystem.resolve(TARGET.getBasePath().resolve("libs/PLATFORM2/libtest-PLATFORM2.a")),
        pathResolver.getAbsolutePath(
            PrebuiltCxxLibraryDescription.getStaticLibraryPath(
                TARGET,
                cellRoots,
                filesystem,
                resolver,
                platform2,
                Optional.empty(),
                libDir,
                libName)));
  }

  @Test
  public void exportedHeaders() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET)
            .setExportedHeaders(
                SourceList.ofNamedSources(
                    ImmutableSortedMap.of("foo.h", new FakeSourcePath("foo.h"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput input = lib.getCxxPreprocessorInput(CXX_PLATFORM, HeaderVisibility.PUBLIC);
    assertThat(getHeaderNames(input.getIncludes()), Matchers.hasItem(filesystem.getPath("foo.h")));
    assertThat(
        ImmutableSortedSet.copyOf(input.getDeps(resolver, ruleFinder)),
        Matchers.equalTo(resolver.getAllRules(getInputRules(lib))));
  }

  @Test
  public void exportedPlatformHeaders() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET)
            .setExportedPlatformHeaders(
                PatternMatchedCollection.<SourceList>builder()
                    .add(
                        Pattern.compile(CXX_PLATFORM.getFlavor().toString()),
                        SourceList.ofNamedSources(
                            ImmutableSortedMap.of("foo.h", new FakeSourcePath("foo.h"))))
                    .add(
                        Pattern.compile("DO NOT MATCH ANYTNING"),
                        SourceList.ofNamedSources(
                            ImmutableSortedMap.of("bar.h", new FakeSourcePath("bar.h"))))
                    .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput input = lib.getCxxPreprocessorInput(CXX_PLATFORM, HeaderVisibility.PUBLIC);
    assertThat(getHeaderNames(input.getIncludes()), Matchers.hasItem(filesystem.getPath("foo.h")));
    assertThat(
        getHeaderNames(input.getIncludes()),
        Matchers.not(Matchers.hasItem(filesystem.getPath("bar.h"))));
    assertThat(
        ImmutableSortedSet.copyOf(input.getDeps(resolver, ruleFinder)),
        Matchers.equalTo(resolver.getAllRules(getInputRules(lib))));
  }

  @Test
  public void testBuildSharedWithDep() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxPlatform platform = CxxPlatformUtils.DEFAULT_PLATFORM;
    BuildTarget target =
        BuildTargetFactory.newInstance("//:x")
            .withFlavors(platform.getFlavor(), CxxDescriptionEnhancer.SHARED_FLAVOR);

    GenruleBuilder genruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_libx"))
            .setOut("gen_libx")
            .setCmd("something");
    PrebuiltCxxLibraryBuilder builder =
        new PrebuiltCxxLibraryBuilder(target).setLibName("x").setLibDir("$(location //:gen_libx)");

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(genruleBuilder.build(), builder.build());

    BuildRule genSrc = genruleBuilder.build(resolver, filesystem, targetGraph);
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    filesystem.writeContentsToPath(
        "class Test {}",
        pathResolver
            .getAbsolutePath(Preconditions.checkNotNull(genSrc.getSourcePathToOutput()))
            .resolve("libx.so"));

    CxxLink lib = (CxxLink) builder.build(resolver, filesystem, targetGraph);
    assertNotNull(lib);
    assertThat(lib.getBuildDeps(), Matchers.contains(genSrc));
  }

  @Test
  public void headerNamespace() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET)
            .setHeaderNamespace("hello")
            .setExportedHeaders(
                SourceList.ofUnnamedSources(ImmutableSortedSet.of(new FakeSourcePath("foo.h"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput input = lib.getCxxPreprocessorInput(CXX_PLATFORM, HeaderVisibility.PUBLIC);
    assertThat(
        getHeaderNames(input.getIncludes()),
        Matchers.contains(filesystem.getPath("hello", "foo.h")));
  }

  @Test
  public void staticPicLibsUseCorrectPath() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC_PIC);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs(), pathResolver).get(0),
        Matchers.endsWith(
            getStaticPicLibraryPath(libBuilder.build().getConstructorArg()).toString()));
  }

  @Test
  public void missingStaticPicLibsUseStaticLibs() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    filesystem.touch(
        filesystem.resolve(getStaticPicLibraryPath(libBuilder.build().getConstructorArg())));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC_PIC);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs(), pathResolver).get(0),
        Matchers.endsWith(
            getStaticPicLibraryPath(libBuilder.build().getConstructorArg()).toString()));
  }

  @Test
  public void forceStatic() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder builder = new PrebuiltCxxLibraryBuilder(TARGET).setForceStatic(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary prebuiltCxxLibrary =
        (PrebuiltCxxLibrary) builder.build(resolver, filesystem, targetGraph);
    assertThat(
        prebuiltCxxLibrary.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.equalTo(NativeLinkable.Linkage.STATIC));
  }

  @Test
  public void exportedLinkerFlagsAreUsedToBuildSharedLibrary() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target =
        BuildTarget.builder(BuildTargetFactory.newInstance("//:lib"))
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .addFlavors(CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor())
            .build();
    PrebuiltCxxLibraryBuilder builder =
        new PrebuiltCxxLibraryBuilder(target)
            .setExportedLinkerFlags(ImmutableList.of("--some-flag"))
            .setForceStatic(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    CxxLink cxxLink = (CxxLink) builder.build(resolver, filesystem, targetGraph);
    assertThat(Arg.stringify(cxxLink.getArgs(), pathResolver), Matchers.hasItem("--some-flag"));
  }

  @Test
  public void nativeLinkableDeps() throws Exception {
    PrebuiltCxxLibraryBuilder depBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"));
    PrebuiltCxxLibraryBuilder ruleBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:r"))
            .setDeps(ImmutableSortedSet.of(depBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(depBuilder.build(), ruleBuilder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary dep =
        (PrebuiltCxxLibrary) depBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) ruleBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        rule.getNativeLinkableDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.contains(dep));
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableExportedDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)),
        empty());
  }

  @Test
  public void nativeLinkableExportedDeps() throws Exception {
    PrebuiltCxxLibraryBuilder depBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"));
    PrebuiltCxxLibraryBuilder ruleBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:r"))
            .setExportedDeps(ImmutableSortedSet.of(depBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(depBuilder.build(), ruleBuilder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary dep =
        (PrebuiltCxxLibrary) depBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) ruleBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)),
        empty());
    assertThat(
        rule.getNativeLinkableExportedDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.contains(dep));
  }

  @Test
  public void includesDirs() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:r"))
            .setIncludeDirs(ImmutableList.of("include"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        rule.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, HeaderVisibility.PUBLIC)
            .getIncludes(),
        Matchers.contains(
            CxxHeadersDir.of(
                CxxPreprocessables.IncludeType.SYSTEM,
                new PathSourcePath(
                    filesystem, rule.getBuildTarget().getBasePath().resolve("include")))));
  }

  @Test
  public void ruleWithoutHeadersDoesNotUseSymlinkTree() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setIncludeDirs(ImmutableList.of());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    CxxPreprocessorInput input =
        rule.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, HeaderVisibility.PUBLIC);
    assertThat(getHeaderNames(input.getIncludes()), empty());
    assertThat(ImmutableList.copyOf(input.getDeps(resolver, ruleFinder)), empty());
  }

  @Test
  public void linkWithoutSoname() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setLinkWithoutSoname(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput input =
        rule.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED);
    assertThat(
        Arg.stringify(input.getArgs(), pathResolver),
        Matchers.contains(
            "-L" + filesystem.resolve(rule.getBuildTarget().getBasePath()).resolve("lib"),
            "-lrule"));
  }

  @Test
  public void missingStaticLibIsNotANativeLinkTargetSoname() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertFalse(rule.getNativeLinkTarget(CXX_PLATFORM).isPresent());
  }

  @Test
  public void providedLibIsNotANativeLinkTargetSoname() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule")).setProvided(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertFalse(rule.getNativeLinkTarget(CXX_PLATFORM).isPresent());
  }

  @Test
  public void existingStaticLibIsANativeLinkTargetSoname() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertTrue(rule.getNativeLinkTarget(CXX_PLATFORM).isPresent());
  }

  @Test
  public void nativeLinkTargetMode() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setSoname("libsoname.so");
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        rule.getNativeLinkTarget(CXX_PLATFORM).get().getNativeLinkTargetMode(CXX_PLATFORM),
        Matchers.equalTo(NativeLinkTargetMode.library("libsoname.so")));
  }

  @Test
  public void nativeLinkTargetDeps() throws Exception {
    PrebuiltCxxLibraryBuilder depBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"));
    PrebuiltCxxLibraryBuilder exportedDepBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:exported_dep"));
    PrebuiltCxxLibraryBuilder ruleBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setExportedDeps(
                ImmutableSortedSet.of(depBuilder.getTarget(), exportedDepBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            depBuilder.build(), exportedDepBuilder.build(), ruleBuilder.build());
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary dep =
        (PrebuiltCxxLibrary) depBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibrary exportedDep =
        (PrebuiltCxxLibrary) exportedDepBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) ruleBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkTarget(CXX_PLATFORM).get().getNativeLinkTargetDeps(CXX_PLATFORM)),
        Matchers.hasItems(dep, exportedDep));
  }

  @Test
  public void nativeLinkTargetInput() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder ruleBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setExportedLinkerFlags(ImmutableList.of("--exported-flag"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(ruleBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) ruleBuilder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput input =
        rule.getNativeLinkTarget(CXX_PLATFORM)
            .get()
            .getNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(Arg.stringify(input.getArgs(), pathResolver), Matchers.hasItems("--exported-flag"));
  }

  @Test
  public void missingStaticLibPrefersSharedLinking() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        rule.getPreferredLinkage(CXX_PLATFORM), Matchers.equalTo(NativeLinkable.Linkage.SHARED));
  }

  @Test
  public void providedDoNotReturnSharedLibs() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule")).setProvided(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(rule.getSharedLibraries(CXX_PLATFORM).entrySet(), empty());
  }

  @Test
  public void headerOnlyLibPrefersAnyLinking() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setHeaderOnly(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        rule.getPreferredLinkage(CXX_PLATFORM), Matchers.equalTo(NativeLinkable.Linkage.ANY));
  }

  @Test
  public void supportedPlatforms() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");

    // First, make sure without any platform regex, we get something back for each of the interface
    // methods.
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder = new PrebuiltCxxLibraryBuilder(target);
    TargetGraph targetGraph1 = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver1 =
        new BuildRuleResolver(targetGraph1, new DefaultTargetNodeToBuildRuleTransformer());
    PrebuiltCxxLibrary prebuiltCxxLibrary =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver1, filesystem, targetGraph1);
    assertThat(
        prebuiltCxxLibrary.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM).entrySet(),
        Matchers.not(empty()));
    assertThat(
        prebuiltCxxLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED)
            .getArgs(),
        Matchers.not(empty()));

    // Now, verify we get nothing when the supported platform regex excludes our platform.
    prebuiltCxxLibraryBuilder.setSupportedPlatformsRegex(Pattern.compile("nothing"));
    TargetGraph targetGraph2 = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver2 =
        new BuildRuleResolver(targetGraph2, new DefaultTargetNodeToBuildRuleTransformer());
    prebuiltCxxLibrary =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver2, filesystem, targetGraph2);
    assertThat(
        prebuiltCxxLibrary.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM).entrySet(),
        empty());
    assertThat(
        prebuiltCxxLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED)
            .getArgs(),
        empty());
  }

  @Test
  public void versionSubDir() throws Exception {
    BuildTarget dep = BuildTargetFactory.newInstance("//:dep");
    PrebuiltCxxLibraryBuilder depBuilder = new PrebuiltCxxLibraryBuilder(dep);
    PrebuiltCxxLibraryBuilder builder = new PrebuiltCxxLibraryBuilder(TARGET);
    builder.setSelectedVersions(ImmutableMap.of(dep, Version.of("1.0")));
    builder.setVersionedSubDir(
        VersionMatchedCollection.<String>builder()
            .add(ImmutableMap.of(dep, Version.of("1.0")), "sub-dir")
            .build());
    TargetGraph graph = TargetGraphFactory.newInstance(depBuilder.build(), builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(graph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    depBuilder.build(resolver, filesystem, graph);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) builder.build(resolver, filesystem, graph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs(), pathResolver).get(0),
        Matchers.equalTo(
            filesystem
                .resolve("sub-dir")
                .resolve(getStaticLibraryPath(builder.build().getConstructorArg()))
                .toString()));
  }
}
