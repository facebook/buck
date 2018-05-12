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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.versions.Version;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PrebuiltCxxLibraryDescriptionTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:target");
  private static final BuildTarget TARGET_TWO = BuildTargetFactory.newInstance("//two:target");
  private static final CxxPlatform CXX_PLATFORM = CxxPlatformUtils.DEFAULT_PLATFORM;

  private static String getSharedLibrarySoname(PrebuiltCxxLibraryDescriptionArg arg) {
    String libName = TARGET.getShortName();
    return arg.getSoname()
        .orElse(String.format("lib%s.%s", libName, CXX_PLATFORM.getSharedLibraryExtension()));
  }

  private static ImmutableSet<BuildTarget> getInputRules(BuildRule buildRule) {
    return ImmutableSet.of(
        buildRule
            .getBuildTarget()
            .withAppendedFlavors(
                CXX_PLATFORM.getFlavor(),
                CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR));
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
  public void createBuildRuleDefault() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET)
            .setStaticLib(FakeSourcePath.of("libfoo.a"))
            .setSharedLib(FakeSourcePath.of("libfoo.so"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput =
        NativeLinkableInput.of(
            ImmutableList.of(
                FileListableLinkerInputArg.withSourcePathArg(
                    SourcePathArg.of(
                        PathSourcePath.of(filesystem, TARGET.getBasePath().resolve("libfoo.a"))))),
            ImmutableSet.of(),
            ImmutableSet.of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC, resolver));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput =
        NativeLinkableInput.of(
            ImmutableList.of(
                SourcePathArg.of(
                    PathSourcePath.of(filesystem, TARGET.getBasePath().resolve("libfoo.so")))),
            ImmutableSet.of(),
            ImmutableSet.of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED, resolver));
  }

  @Test
  public void headerOnly() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET).setHeaderOnly(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput =
        NativeLinkableInput.of(ImmutableList.of(), ImmutableSet.of(), ImmutableSet.of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC, resolver));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput =
        NativeLinkableInput.of(ImmutableList.of(), ImmutableSet.of(), ImmutableSet.of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED, resolver));
  }

  @Test
  public void createBuildRuleExternal() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET)
            .setProvided(true)
            .setSharedLib(FakeSourcePath.of("libfoo.so"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput =
        NativeLinkableInput.of(
            ImmutableList.of(
                SourcePathArg.of(
                    PathSourcePath.of(filesystem, TARGET.getBasePath().resolve("libfoo.so")))),
            ImmutableSet.of(),
            ImmutableSet.of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED, resolver));
  }

  @Test
  public void missingSharedLibsAreAutoBuilt() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET).setStaticLib(FakeSourcePath.of("libfoo.a"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED, resolver);
    BuildRule rule =
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> BuildableSupport.getDepsCollection(arg, ruleFinder))
            .toList()
            .get(0);
    assertTrue(rule instanceof CxxLink);
  }

  @Test
  public void missingSharedLibsAreNotAutoBuiltForHeaderOnlyRules() throws Exception {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET).setHeaderOnly(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED, resolver);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> BuildableSupport.getDepsCollection(arg, ruleFinder))
            .toList(),
        empty());
  }

  @Test
  public void addsLibsToAndroidPackageableCollector() throws Exception {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET).setSharedLib(FakeSourcePath.of("libfoo.so"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescriptionArg arg = libBuilder.build().getConstructorArg();
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            getSharedLibrarySoname(arg),
            PathSourcePath.of(filesystem, TARGET.getBasePath().resolve("libfoo.so"))),
        lib.getSharedLibraries(CXX_PLATFORM, resolver));
  }

  @Test
  public void locationMacro() throws NoSuchBuildTargetException {
    GenruleBuilder genruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//other:gen_lib"))
            .setOut("libtest.a");
    PrebuiltCxxLibraryBuilder libraryBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET)
            .setStaticLib(DefaultBuildTargetSourcePath.of(genruleBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(genruleBuilder.build(), libraryBuilder.build());
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    Genrule genrule = genruleBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibrary library =
        (PrebuiltCxxLibrary) libraryBuilder.build(resolver, filesystem, targetGraph);
    SourcePath staticLibraryPath =
        library.getStaticLibrary(CxxPlatformUtils.DEFAULT_PLATFORM, resolver).get();
    assertThat(
        pathResolver.getAbsolutePath(staticLibraryPath),
        Matchers.equalTo(pathResolver.getAbsolutePath(genrule.getSourcePathToOutput())));
  }

  @Test
  public void locationMacroWithCxxGenrule() throws NoSuchBuildTargetException {
    CxxGenruleBuilder genruleBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//other:gen_lib"))
            .setOut("libtest.a");
    PrebuiltCxxLibraryBuilder libraryBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET)
            .setStaticLib(DefaultBuildTargetSourcePath.of(genruleBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(genruleBuilder.build(), libraryBuilder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    CxxGenrule genrule = (CxxGenrule) genruleBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibrary library =
        (PrebuiltCxxLibrary) libraryBuilder.build(resolver, filesystem, targetGraph);
    SourcePath staticLibraryPath =
        library.getStaticLibrary(CxxPlatformUtils.DEFAULT_PLATFORM, resolver).get();
    assertThat(
        pathResolver.getAbsolutePath(staticLibraryPath),
        Matchers.equalTo(
            pathResolver.getAbsolutePath(
                genrule.getGenrule(CxxPlatformUtils.DEFAULT_PLATFORM, resolver))));
  }

  @Test
  public void goodPathNoLocation() throws NoSuchBuildTargetException {
    PrebuiltCxxLibraryBuilder libraryBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET_TWO)
            .setStaticLib(FakeSourcePath.of("two/lib/libtarget.a"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryBuilder.build());
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    PrebuiltCxxLibrary library =
        (PrebuiltCxxLibrary) libraryBuilder.build(resolver, filesystem, targetGraph);
    SourcePath staticLibraryPath =
        library.getStaticLibrary(CxxPlatformUtils.DEFAULT_PLATFORM, resolver).get();
    assertThat(
        MorePaths.pathWithUnixSeparators(pathResolver.getAbsolutePath(staticLibraryPath)),
        Matchers.containsString(String.format("two/%s/libtarget.a", "lib")));
  }

  @Test
  public void findDepsFromParamsWithLocation() throws NoSuchBuildTargetException {
    BuildTarget genTarget = BuildTargetFactory.newInstance("//other:gen_lib");
    GenruleBuilder genruleBuilder =
        GenruleBuilder.newGenruleBuilder(genTarget).setOut("libtest.so");

    PrebuiltCxxLibraryBuilder builder = new PrebuiltCxxLibraryBuilder(TARGET);
    builder.setSoname("test");
    builder.setStaticLib(DefaultBuildTargetSourcePath.of(genTarget));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(genruleBuilder.build(), builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);

    BuildRule genrule = genruleBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) builder.build(resolver, filesystem, targetGraph);

    ImmutableSortedSet<BuildTarget> implicit = builder.build().getExtraDeps();
    assertEquals(ImmutableSortedSet.of(genTarget), implicit);

    assertThat(lib.getBuildDeps(), contains(genrule));
  }

  @Test
  public void findTargetOnlyDepsFromParamsWithPlatform() {
    BuildTarget platform1Dep = BuildTargetFactory.newInstance("//platform1:dep");
    BuildTarget platform2Dep = BuildTargetFactory.newInstance("//platform2:dep");
    PrebuiltCxxLibraryBuilder builder = new PrebuiltCxxLibraryBuilder(TARGET);
    builder.setSoname("test");
    builder.setPlatformStaticLib(
        PatternMatchedCollection.<SourcePath>builder()
            .add(
                Pattern.compile("platform 1", Pattern.LITERAL),
                DefaultBuildTargetSourcePath.of(platform1Dep))
            .add(
                Pattern.compile("platform 2", Pattern.LITERAL),
                DefaultBuildTargetSourcePath.of(platform2Dep))
            .build());
    assertThat(builder.build().getTargetGraphOnlyDeps(), contains(platform1Dep, platform2Dep));
  }

  @Test
  public void platformMacro() throws NoSuchBuildTargetException {
    CxxPlatform platform1 =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("PLATFORM1"));
    CxxPlatform platform2 =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("PLATFORM2"));
    FlavorDomain<CxxPlatform> platforms =
        new FlavorDomain<>(
            "C/C++",
            ImmutableMap.of(platform1.getFlavor(), platform1, platform2.getFlavor(), platform2));

    PrebuiltCxxLibraryBuilder libraryBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET, platforms)
            .setPlatformStaticLib(
                PatternMatchedCollection.<SourcePath>builder()
                    .add(
                        Pattern.compile(platform1.getFlavor().toString(), Pattern.LITERAL),
                        FakeSourcePath.of("libs/PLATFORM1/libtest-PLATFORM1.a"))
                    .add(
                        Pattern.compile(platform2.getFlavor().toString(), Pattern.LITERAL),
                        FakeSourcePath.of("libs/PLATFORM2/libtest-PLATFORM2.a"))
                    .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

    PrebuiltCxxLibrary library =
        (PrebuiltCxxLibrary) libraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        pathResolver.getRelativePath(library.getStaticLibrary(platform1, resolver).get()),
        Matchers.equalTo(TARGET.getBasePath().resolve("libs/PLATFORM1/libtest-PLATFORM1.a")));
    assertThat(
        pathResolver.getRelativePath(library.getStaticLibrary(platform2, resolver).get()),
        Matchers.equalTo(TARGET.getBasePath().resolve("libs/PLATFORM2/libtest-PLATFORM2.a")));
  }

  @Test
  public void exportedHeaders() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET)
            .setExportedHeaders(
                SourceList.ofNamedSources(
                    ImmutableSortedMap.of("foo.h", FakeSourcePath.of("foo.h"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput input = lib.getCxxPreprocessorInput(CXX_PLATFORM, resolver);
    assertThat(getHeaderNames(input.getIncludes()), Matchers.hasItem(filesystem.getPath("foo.h")));
    assertThat(
        ImmutableSortedSet.copyOf(input.getDeps(resolver, ruleFinder)),
        Matchers.equalTo(resolver.getAllRules(getInputRules(lib))));
  }

  @Test
  public void exportedPlatformHeaders() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET)
            .setExportedPlatformHeaders(
                PatternMatchedCollection.<SourceList>builder()
                    .add(
                        Pattern.compile(CXX_PLATFORM.getFlavor().toString()),
                        SourceList.ofNamedSources(
                            ImmutableSortedMap.of("foo.h", FakeSourcePath.of("foo.h"))))
                    .add(
                        Pattern.compile("DO NOT MATCH ANYTNING"),
                        SourceList.ofNamedSources(
                            ImmutableSortedMap.of("bar.h", FakeSourcePath.of("bar.h"))))
                    .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput input = lib.getCxxPreprocessorInput(CXX_PLATFORM, resolver);
    assertThat(getHeaderNames(input.getIncludes()), Matchers.hasItem(filesystem.getPath("foo.h")));
    assertThat(
        getHeaderNames(input.getIncludes()),
        Matchers.not(Matchers.hasItem(filesystem.getPath("bar.h"))));
    assertThat(
        ImmutableSortedSet.copyOf(input.getDeps(resolver, ruleFinder)),
        Matchers.equalTo(resolver.getAllRules(getInputRules(lib))));
  }

  @Test
  public void testBuildSharedWithDep() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    CxxPlatform platform = CxxPlatformUtils.DEFAULT_PLATFORM;
    BuildTarget target =
        BuildTargetFactory.newInstance("//:x")
            .withFlavors(platform.getFlavor(), CxxDescriptionEnhancer.SHARED_FLAVOR);

    GenruleBuilder genruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_libx"))
            .setOut("libx.a")
            .setCmd("something");
    PrebuiltCxxLibraryBuilder builder =
        new PrebuiltCxxLibraryBuilder(target)
            .setStaticLib(DefaultBuildTargetSourcePath.of(genruleBuilder.getTarget()));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(genruleBuilder.build(), builder.build());

    BuildRule genSrc = genruleBuilder.build(resolver, filesystem, targetGraph);

    CxxLink lib = (CxxLink) builder.build(resolver, filesystem, targetGraph);
    assertNotNull(lib);
    assertThat(lib.getBuildDeps(), contains(genSrc));
  }

  @Test
  public void headerNamespace() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET)
            .setHeaderNamespace("hello")
            .setExportedHeaders(
                SourceList.ofUnnamedSources(ImmutableSortedSet.of(FakeSourcePath.of("foo.h"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput input = lib.getCxxPreprocessorInput(CXX_PLATFORM, resolver);
    assertThat(getHeaderNames(input.getIncludes()), contains(filesystem.getPath("hello", "foo.h")));
  }

  @Test
  public void staticPicLibsUseCorrectPath() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET).setStaticPicLib(FakeSourcePath.of("libfoo_pic.a"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC_PIC, resolver);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs(), pathResolver).get(0),
        Matchers.endsWith("libfoo_pic.a"));
  }

  @Test
  public void missingStaticPicLibsUseStaticLibs() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary)
            libBuilder
                .setStaticLib(FakeSourcePath.of("libfoo.a"))
                .build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC_PIC, resolver);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs(), pathResolver).get(0),
        Matchers.endsWith("libfoo.a"));
  }

  @Test(expected = UncheckedExecutionException.class)
  public void missingAllLibFilesThrowsUsefulException() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder =
        new PrebuiltCxxLibraryBuilder(TARGET).setForceStatic(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC_PIC, resolver);
  }

  @Test
  public void forceStatic() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder builder =
        new PrebuiltCxxLibraryBuilder(TARGET)
            .setForceStatic(true)
            .setSharedLib(FakeSourcePath.of("libfoo.so"))
            .setStaticLib(FakeSourcePath.of("libfoo.a"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary prebuiltCxxLibrary =
        (PrebuiltCxxLibrary) builder.build(resolver, filesystem, targetGraph);
    assertThat(
        prebuiltCxxLibrary.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM, resolver),
        Matchers.equalTo(NativeLinkable.Linkage.STATIC));
  }

  @Test
  public void exportedLinkerFlagsAreUsedToBuildSharedLibrary() {
    PrebuiltCxxLibraryBuilder builder =
        new PrebuiltCxxLibraryBuilder(TARGET)
            .setExportedLinkerFlags(ImmutableList.of("--some-flag"))
            .setStaticLib(FakeSourcePath.of("libfoo.a"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    CxxLink cxxLink =
        (CxxLink)
            resolver.requireRule(
                TARGET.withAppendedFlavors(
                    CxxDescriptionEnhancer.SHARED_FLAVOR,
                    CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor()));
    assertThat(Arg.stringify(cxxLink.getArgs(), pathResolver), Matchers.hasItem("--some-flag"));
  }

  @Test
  public void nativeLinkableDeps() {
    PrebuiltCxxLibraryBuilder depBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"));
    PrebuiltCxxLibraryBuilder ruleBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:r"))
            .setDeps(ImmutableSortedSet.of(depBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(depBuilder.build(), ruleBuilder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary dep =
        (PrebuiltCxxLibrary) depBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) ruleBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        rule.getNativeLinkableDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM, resolver),
        contains(dep));
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableExportedDepsForPlatform(
                CxxPlatformUtils.DEFAULT_PLATFORM, resolver)),
        empty());
  }

  @Test
  public void nativeLinkableExportedDeps() {
    PrebuiltCxxLibraryBuilder depBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"));
    PrebuiltCxxLibraryBuilder ruleBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:r"))
            .setExportedDeps(ImmutableSortedSet.of(depBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(depBuilder.build(), ruleBuilder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary dep =
        (PrebuiltCxxLibrary) depBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) ruleBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM, resolver)),
        empty());
    assertThat(
        rule.getNativeLinkableExportedDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM, resolver),
        contains(dep));
  }

  @Test
  public void includesDirs() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:r"))
            .setHeaderDirs(ImmutableList.of(FakeSourcePath.of("include")));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        rule.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, resolver).getIncludes(),
        contains(
            CxxHeadersDir.of(
                CxxPreprocessables.IncludeType.SYSTEM,
                PathSourcePath.of(
                    filesystem,
                    rule.getBuildTarget().getBasePath().getFileSystem().getPath("include")))));
  }

  @Test
  public void ruleWithoutHeadersDoesNotUseSymlinkTree() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setHeaderDirs(ImmutableList.of());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    CxxPreprocessorInput input =
        rule.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, resolver);
    assertThat(getHeaderNames(input.getIncludes()), empty());
    assertThat(ImmutableList.copyOf(input.getDeps(resolver, ruleFinder)), empty());
  }

  @Test
  public void linkWithoutSoname() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setSharedLib(FakeSourcePath.of("lib/librule.so"))
            .setLinkWithoutSoname(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput input =
        rule.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED, resolver);
    assertThat(
        Arg.stringify(input.getArgs(), pathResolver),
        contains(
            "-L" + filesystem.resolve(rule.getBuildTarget().getBasePath()).resolve("lib"),
            "-lrule"));
  }

  @Test
  public void sharedLinkageIsNotANativeLinkTargetSoname() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setPreferredLinkage(NativeLinkable.Linkage.SHARED)
            .setSharedLib(FakeSourcePath.of("libfoo.so"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertFalse(rule.getNativeLinkTarget(CXX_PLATFORM, resolver).isPresent());
  }

  @Test
  public void providedLibIsNotANativeLinkTargetSoname() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setProvided(true)
            .setSharedLib(FakeSourcePath.of("libfoo.so"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertFalse(rule.getNativeLinkTarget(CXX_PLATFORM, resolver).isPresent());
  }

  @Test
  public void existingStaticLibIsANativeLinkTargetSoname() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertTrue(rule.getNativeLinkTarget(CXX_PLATFORM, resolver).isPresent());
  }

  @Test
  public void nativeLinkTargetMode() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setSoname("libsoname.so");
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        rule.getNativeLinkTarget(CXX_PLATFORM, resolver)
            .get()
            .getNativeLinkTargetMode(CXX_PLATFORM),
        Matchers.equalTo(NativeLinkTargetMode.library("libsoname.so")));
  }

  @Test
  public void nativeLinkTargetDeps() {
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
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary dep =
        (PrebuiltCxxLibrary) depBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibrary exportedDep =
        (PrebuiltCxxLibrary) exportedDepBuilder.build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) ruleBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkTarget(CXX_PLATFORM, resolver)
                .get()
                .getNativeLinkTargetDeps(CXX_PLATFORM, resolver)),
        Matchers.hasItems(dep, exportedDep));
  }

  @Test
  public void nativeLinkTargetInput() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder ruleBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setStaticPicLib(FakeSourcePath.of("libfoo_pic.a"))
            .setExportedLinkerFlags(ImmutableList.of("--exported-flag"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(ruleBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) ruleBuilder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput input =
        rule.getNativeLinkTarget(CXX_PLATFORM, resolver)
            .get()
            .getNativeLinkTargetInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, resolver, pathResolver, ruleFinder);
    assertThat(Arg.stringify(input.getArgs(), pathResolver), Matchers.hasItems("--exported-flag"));
  }

  @Test
  public void providedDoNotReturnSharedLibs() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule")).setProvided(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(rule.getSharedLibraries(CXX_PLATFORM, resolver).entrySet(), empty());
  }

  @Test
  public void headerOnlyLibPrefersAnyLinking() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setHeaderOnly(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        rule.getPreferredLinkage(CXX_PLATFORM, resolver),
        Matchers.equalTo(NativeLinkable.Linkage.ANY));
  }

  @Test
  public void supportedPlatforms() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");

    // First, make sure without any platform regex, we get something back for each of the interface
    // methods.
    PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
        new PrebuiltCxxLibraryBuilder(target).setSharedLib(FakeSourcePath.of("libfoo.so"));
    TargetGraph targetGraph1 = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver1 = new TestBuildRuleResolver(targetGraph1);
    PrebuiltCxxLibrary prebuiltCxxLibrary =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver1, filesystem, targetGraph1);
    assertThat(
        prebuiltCxxLibrary
            .getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM, resolver1)
            .entrySet(),
        Matchers.not(empty()));
    assertThat(
        prebuiltCxxLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED, resolver1)
            .getArgs(),
        Matchers.not(empty()));

    // Now, verify we get nothing when the supported platform regex excludes our platform.
    prebuiltCxxLibraryBuilder
        .setSupportedPlatformsRegex(Pattern.compile("nothing"))
        .setSharedLib(FakeSourcePath.of("libfoo.so"));
    TargetGraph targetGraph2 = TargetGraphFactory.newInstance(prebuiltCxxLibraryBuilder.build());
    BuildRuleResolver resolver2 = new TestBuildRuleResolver(targetGraph2);
    prebuiltCxxLibrary =
        (PrebuiltCxxLibrary) prebuiltCxxLibraryBuilder.build(resolver2, filesystem, targetGraph2);
    assertThat(
        prebuiltCxxLibrary
            .getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM, resolver2)
            .entrySet(),
        empty());
    assertThat(
        prebuiltCxxLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED, resolver2)
            .getArgs(),
        empty());
  }

  @Test
  public void versionedLibrary() {
    BuildTarget dep = BuildTargetFactory.newInstance("//:dep");
    PrebuiltCxxLibraryBuilder depBuilder = new PrebuiltCxxLibraryBuilder(dep);
    PrebuiltCxxLibraryBuilder builder = new PrebuiltCxxLibraryBuilder(TARGET);
    builder.setSelectedVersions(ImmutableMap.of(dep, Version.of("1.0")));
    builder.setVersionedStaticLib(
        VersionMatchedCollection.<SourcePath>builder()
            .add(ImmutableMap.of(dep, Version.of("1.0")), FakeSourcePath.of("sub-dir/libfoo.a"))
            .build());
    TargetGraph graph = TargetGraphFactory.newInstance(depBuilder.build(), builder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(graph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    depBuilder.build(resolver, filesystem, graph);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) builder.build(resolver, filesystem, graph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC, resolver);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs(), pathResolver).get(0),
        Matchers.endsWith(TARGET.getBasePath().resolve("sub-dir").resolve("libfoo.a").toString()));
  }
}
