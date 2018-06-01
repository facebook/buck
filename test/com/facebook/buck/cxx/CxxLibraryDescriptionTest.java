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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.DependencyAggregationTestUtil;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.shell.ExportFile;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CxxLibraryDescriptionTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(
        new Object[] {"sandbox_sources=false"}, new Object[] {"sandbox_sources=true"});
  }

  public CxxLibraryDescriptionTest(String sandboxConfig) {
    this.cxxBuckConfig =
        new CxxBuckConfig(FakeBuckConfig.builder().setSections("[cxx]", sandboxConfig).build());
  }

  private final CxxBuckConfig cxxBuckConfig;

  private static Optional<SourcePath> getHeaderMaps(
      ProjectFilesystem filesystem,
      BuildTarget target,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    if (cxxPlatform.getCpp().resolve(resolver).supportsHeaderMaps()
        && cxxPlatform.getCxxpp().resolve(resolver).supportsHeaderMaps()) {
      BuildTarget headerMapBuildTarget =
          CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
              target, headerVisibility, cxxPlatform.getFlavor());
      return Optional.of(
          ExplicitBuildTargetSourcePath.of(
              headerMapBuildTarget,
              HeaderSymlinkTreeWithHeaderMap.getPath(filesystem, headerMapBuildTarget)));
    } else {
      return Optional.empty();
    }
  }

  private ImmutableSet<Path> getHeaderNames(Iterable<CxxHeaders> includes) {
    ImmutableSet.Builder<Path> names = ImmutableSet.builder();
    for (CxxHeaders headers : includes) {
      CxxSymlinkTreeHeaders symlinkTreeHeaders = (CxxSymlinkTreeHeaders) headers;
      names.addAll(symlinkTreeHeaders.getNameToPathMap().keySet());
    }
    return names.build();
  }

  @Test
  public void createBuildRule() {
    Assume.assumeFalse("This test assumes no sandboxing", cxxBuckConfig.sandboxSources());

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;

    // Setup a genrule the generates a header we'll list.
    String genHeaderName = "test/foo.h";
    BuildTarget genHeaderTarget = BuildTargetFactory.newInstance("//:genHeader");
    GenruleBuilder genHeaderBuilder =
        GenruleBuilder.newGenruleBuilder(genHeaderTarget).setOut(genHeaderName);

    // Setup a genrule the generates a source we'll list.
    String genSourceName = "test/foo.cpp";
    BuildTarget genSourceTarget = BuildTargetFactory.newInstance("//:genSource");
    GenruleBuilder genSourceBuilder =
        GenruleBuilder.newGenruleBuilder(genSourceTarget).setOut(genSourceName);

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    BuildTarget sharedDepTarget =
        BuildTargetFactory.newInstance("//:sharedDep")
            .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR);
    CxxLibraryBuilder sharedDepBuilder =
        new CxxLibraryBuilder(sharedDepTarget, cxxBuckConfig)
            .setExportedHeaders(
                SourceList.ofUnnamedSources(ImmutableSortedSet.of(FakeSourcePath.of("blah.h"))))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test_shared.cpp"))));
    BuildTarget sharedHeaderSymlinkTreeTarget =
        sharedDepTarget.withFlavors(
            CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
            HeaderMode.SYMLINK_TREE_ONLY.getFlavor());

    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    CxxLibraryBuilder depBuilder =
        new CxxLibraryBuilder(depTarget, cxxBuckConfig)
            .setExportedHeaders(
                SourceList.ofUnnamedSources(ImmutableSortedSet.of(FakeSourcePath.of("blah.h"))))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test.cpp"))));
    BuildTarget headerSymlinkTreeTarget =
        depTarget.withAppendedFlavors(
            CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
            HeaderMode.SYMLINK_TREE_ONLY.getFlavor());

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(filesystem.getRootPath(), target, cxxPlatform, cxxBuckConfig);

    String headerName = "test/bar.h";
    String privateHeaderName = "test/bar_private.h";
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(target, cxxBuckConfig)
            .setExportedHeaders(
                ImmutableSortedSet.of(
                    FakeSourcePath.of(headerName),
                    DefaultBuildTargetSourcePath.of(genHeaderTarget)))
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of(privateHeaderName)))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("test/bar.cpp")),
                    SourceWithFlags.of(DefaultBuildTargetSourcePath.of(genSourceTarget))))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourcePath(FakeSourcePath.of("/some/framework/path/s.dylib")),
                    FrameworkPath.ofSourcePath(
                        FakeSourcePath.of("/another/framework/path/a.dylib"))))
            .setDeps(ImmutableSortedSet.of(depTarget, sharedDepTarget));

    // Build the target graph.
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            genHeaderBuilder.build(),
            genSourceBuilder.build(),
            depBuilder.build(),
            sharedDepBuilder.build(),
            cxxLibraryBuilder.build());

    // Build the rules.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    genHeaderBuilder.build(graphBuilder, filesystem, targetGraph);
    genSourceBuilder.build(graphBuilder, filesystem, targetGraph);
    depBuilder.build(graphBuilder, filesystem, targetGraph);
    sharedDepBuilder.build(graphBuilder, filesystem, targetGraph);
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder.build(graphBuilder, filesystem, targetGraph);

    // Verify public preprocessor input.
    CxxPreprocessorInput publicInput = rule.getCxxPreprocessorInput(cxxPlatform, graphBuilder);
    assertThat(
        publicInput.getFrameworks(),
        containsInAnyOrder(
            FrameworkPath.ofSourcePath(
                FakeSourcePath.of(filesystem, "/some/framework/path/s.dylib")),
            FrameworkPath.ofSourcePath(
                FakeSourcePath.of(filesystem, "/another/framework/path/a.dylib"))));
    CxxSymlinkTreeHeaders publicHeaders = (CxxSymlinkTreeHeaders) publicInput.getIncludes().get(0);
    assertThat(publicHeaders.getIncludeType(), equalTo(CxxPreprocessables.IncludeType.LOCAL));
    assertThat(
        publicHeaders.getNameToPathMap(),
        equalTo(
            ImmutableMap.of(
                Paths.get(headerName),
                FakeSourcePath.of(headerName),
                Paths.get(genHeaderName),
                DefaultBuildTargetSourcePath.of(genHeaderTarget))));
    assertThat(
        publicHeaders.getHeaderMap(),
        equalTo(
            getHeaderMaps(filesystem, target, graphBuilder, cxxPlatform, HeaderVisibility.PUBLIC)));

    // Verify private preprocessor input.
    CxxPreprocessorInput privateInput =
        rule.getPrivateCxxPreprocessorInput(cxxPlatform, graphBuilder);
    assertThat(
        privateInput.getFrameworks(),
        containsInAnyOrder(
            FrameworkPath.ofSourcePath(
                FakeSourcePath.of(filesystem, "/some/framework/path/s.dylib")),
            FrameworkPath.ofSourcePath(
                FakeSourcePath.of(filesystem, "/another/framework/path/a.dylib"))));
    CxxSymlinkTreeHeaders privateHeaders =
        (CxxSymlinkTreeHeaders) privateInput.getIncludes().get(0);
    assertThat(privateHeaders.getIncludeType(), equalTo(CxxPreprocessables.IncludeType.LOCAL));
    assertThat(
        privateHeaders.getNameToPathMap(),
        equalTo(
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(privateHeaderName), FakeSourcePath.of(privateHeaderName))));
    assertThat(
        privateHeaders.getHeaderMap(),
        equalTo(
            getHeaderMaps(
                filesystem, target, graphBuilder, cxxPlatform, HeaderVisibility.PRIVATE)));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC, graphBuilder);
    BuildRule archiveRule =
        graphBuilder.getRule(
            CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
                target, cxxPlatform.getFlavor(), PicType.PDC));
    assertNotNull(archiveRule);
    assertEquals(
        ImmutableSet.of(
            cxxSourceRuleFactory.createCompileBuildTarget("test/bar.cpp"),
            cxxSourceRuleFactory.createCompileBuildTarget(genSourceName)),
        archiveRule
            .getBuildDeps()
            .stream()
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule compileRule1 =
        graphBuilder.getRule(cxxSourceRuleFactory.createCompileBuildTarget("test/bar.cpp"));
    assertNotNull(compileRule1);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(compileRule1)
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()),
        containsInAnyOrder(
            genHeaderTarget,
            headerSymlinkTreeTarget,
            sharedHeaderSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor()),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PUBLIC, HeaderMode.SYMLINK_TREE_ONLY.getFlavor())));

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule compileRule2 =
        graphBuilder.getRule(cxxSourceRuleFactory.createCompileBuildTarget(genSourceName));
    assertNotNull(compileRule2);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(compileRule2)
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()),
        containsInAnyOrder(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTreeTarget,
            sharedHeaderSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor()),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PUBLIC, HeaderMode.SYMLINK_TREE_ONLY.getFlavor())));
  }

  @Test
  public void overrideSoname() {

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;

    String soname = "test_soname";

    // Generate the C++ library rules.
    BuildTarget target =
        BuildTargetFactory.newInstance("//:rule")
            .withFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                CxxDescriptionEnhancer.SHARED_FLAVOR,
                LinkerMapMode.NO_LINKER_MAP.getFlavor());
    CxxLibraryBuilder ruleBuilder =
        new CxxLibraryBuilder(target, cxxBuckConfig)
            .setSoname(soname)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.cpp"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(ruleBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    CxxLink rule = (CxxLink) ruleBuilder.build(graphBuilder, filesystem, targetGraph);
    Linker linker = cxxPlatform.getLd().resolve(graphBuilder);
    ImmutableList<String> sonameArgs = ImmutableList.copyOf(linker.soname(soname));
    assertThat(
        Arg.stringify(rule.getArgs(), pathResolver),
        hasItems(sonameArgs.toArray(new String[sonameArgs.size()])));
  }

  @Test
  public void overrideStaticLibraryBasename() {

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    String basename = "test_static_library_basename";

    // Generate the C++ library rules.
    BuildTarget target =
        BuildTargetFactory.newInstance("//:rule")
            .withFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                CxxDescriptionEnhancer.STATIC_FLAVOR);
    CxxLibraryBuilder ruleBuilder =
        new CxxLibraryBuilder(target, cxxBuckConfig)
            .setStaticLibraryBasename(basename)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.cpp"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(ruleBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Archive rule = (Archive) ruleBuilder.build(graphBuilder, filesystem, targetGraph);
    Path path = pathResolver.getAbsolutePath(rule.getSourcePathToOutput());
    assertEquals(
        MorePaths.getNameWithoutExtension(path.getFileName()), "libtest_static_library_basename");
  }

  @Test
  public void linkWhole() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;

    // Setup the target name and build params.
    BuildTarget target = BuildTargetFactory.newInstance("//:test");

    // First, create a cxx library without using link whole.
    CxxLibraryBuilder normalBuilder =
        new CxxLibraryBuilder(target, cxxBuckConfig)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test.cpp"))));
    TargetGraph normalGraph = TargetGraphFactory.newInstance(normalBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(normalGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    CxxLibrary normal = (CxxLibrary) normalBuilder.build(graphBuilder, filesystem, normalGraph);

    // Lookup the link whole flags.
    Linker linker = cxxPlatform.getLd().resolve(graphBuilder);
    ImmutableList<String> linkWholeFlags =
        FluentIterable.from(linker.linkWhole(StringArg.of("sentinel")))
            .transformAndConcat((input1) -> Arg.stringifyList(input1, pathResolver))
            .filter(Predicates.not("sentinel"::equals))
            .toList();

    // Verify that the linker args contains the link whole flags.
    NativeLinkableInput input =
        normal.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC, graphBuilder);
    assertThat(
        Arg.stringify(input.getArgs(), pathResolver),
        not(hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()]))));

    // Create a cxx library using link whole.
    CxxLibraryBuilder linkWholeBuilder =
        new CxxLibraryBuilder(target, cxxBuckConfig)
            .setLinkWhole(true)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.cpp"))));

    TargetGraph linkWholeGraph = TargetGraphFactory.newInstance(linkWholeBuilder.build());
    graphBuilder = new TestActionGraphBuilder(normalGraph);
    CxxLibrary linkWhole =
        (CxxLibrary)
            linkWholeBuilder
                .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test.cpp"))))
                .build(graphBuilder, filesystem, linkWholeGraph);

    // Verify that the linker args contains the link whole flags.
    NativeLinkableInput linkWholeInput =
        linkWhole.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC, graphBuilder);
    assertThat(
        Arg.stringify(linkWholeInput.getArgs(), pathResolver),
        hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()])));
  }

  @Test
  public void createCxxLibraryBuildRules() {
    Assume.assumeFalse("This test assumes no sandboxing", cxxBuckConfig.sandboxSources());

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;

    // Setup a normal C++ source
    String sourceName = "test/bar.cpp";

    // Setup a genrule the generates a header we'll list.
    String genHeaderName = "test/foo.h";
    BuildTarget genHeaderTarget = BuildTargetFactory.newInstance("//:genHeader");
    GenruleBuilder genHeaderBuilder =
        GenruleBuilder.newGenruleBuilder(genHeaderTarget).setOut(genHeaderName);

    // Setup a genrule the generates a source we'll list.
    String genSourceName = "test/foo.cpp";
    BuildTarget genSourceTarget = BuildTargetFactory.newInstance("//:genSource");
    GenruleBuilder genSourceBuilder =
        GenruleBuilder.newGenruleBuilder(genSourceTarget).setOut(genSourceName);

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    CxxLibraryBuilder depBuilder =
        new CxxLibraryBuilder(depTarget, cxxBuckConfig)
            .setExportedHeaders(
                SourceList.ofUnnamedSources(ImmutableSortedSet.of(FakeSourcePath.of("blah.h"))))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test.cpp"))));
    BuildTarget sharedLibraryDepTarget =
        depTarget.withAppendedFlavors(
            CxxDescriptionEnhancer.SHARED_FLAVOR, cxxPlatform.getFlavor());
    BuildTarget headerSymlinkTreeTarget =
        depTarget.withAppendedFlavors(
            CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
            HeaderMode.SYMLINK_TREE_ONLY.getFlavor());

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxSourceRuleFactory cxxSourceRuleFactoryPDC =
        CxxSourceRuleFactoryHelper.of(
            filesystem.getRootPath(), target, cxxPlatform, cxxBuckConfig, PicType.PDC);

    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(target, cxxBuckConfig)
            .setExportedHeaders(
                ImmutableSortedMap.of(
                    genHeaderName, DefaultBuildTargetSourcePath.of(genHeaderTarget)))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of(sourceName)),
                    SourceWithFlags.of(DefaultBuildTargetSourcePath.of(genSourceTarget))))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourcePath(FakeSourcePath.of("/some/framework/path/s.dylib")),
                    FrameworkPath.ofSourcePath(
                        FakeSourcePath.of("/another/framework/path/a.dylib"))))
            .setDeps(ImmutableSortedSet.of(depTarget));

    // Build target graph.
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            genHeaderBuilder.build(),
            genSourceBuilder.build(),
            depBuilder.build(),
            cxxLibraryBuilder.build());

    // Construct C/C++ library build rules.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    genHeaderBuilder.build(graphBuilder, filesystem, targetGraph);
    genSourceBuilder.build(graphBuilder, filesystem, targetGraph);
    depBuilder.build(graphBuilder, filesystem, targetGraph);
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder.build(graphBuilder, filesystem, targetGraph);

    // Verify the C/C++ preprocessor input is setup correctly.
    CxxPreprocessorInput publicInput = rule.getCxxPreprocessorInput(cxxPlatform, graphBuilder);
    assertThat(
        publicInput.getFrameworks(),
        containsInAnyOrder(
            FrameworkPath.ofSourcePath(
                FakeSourcePath.of(filesystem, "/some/framework/path/s.dylib")),
            FrameworkPath.ofSourcePath(
                FakeSourcePath.of(filesystem, "/another/framework/path/a.dylib"))));
    CxxSymlinkTreeHeaders publicHeaders = (CxxSymlinkTreeHeaders) publicInput.getIncludes().get(0);
    assertThat(publicHeaders.getIncludeType(), equalTo(CxxPreprocessables.IncludeType.LOCAL));
    assertThat(
        publicHeaders.getNameToPathMap(),
        equalTo(
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(genHeaderName), DefaultBuildTargetSourcePath.of(genHeaderTarget))));
    assertThat(
        publicHeaders.getHeaderMap(),
        equalTo(
            getHeaderMaps(filesystem, target, graphBuilder, cxxPlatform, HeaderVisibility.PUBLIC)));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC, graphBuilder);
    BuildRule staticRule =
        graphBuilder.getRule(
            CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
                target, cxxPlatform.getFlavor(), PicType.PDC));
    assertNotNull(staticRule);
    assertEquals(
        ImmutableSet.of(
            cxxSourceRuleFactoryPDC.createCompileBuildTarget("test/bar.cpp"),
            cxxSourceRuleFactoryPDC.createCompileBuildTarget(genSourceName)),
        staticRule
            .getBuildDeps()
            .stream()
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule staticCompileRule1 =
        graphBuilder.getRule(cxxSourceRuleFactoryPDC.createCompileBuildTarget("test/bar.cpp"));
    assertNotNull(staticCompileRule1);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(staticCompileRule1)
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()),
        containsInAnyOrder(
            genHeaderTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor()),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PUBLIC, HeaderMode.SYMLINK_TREE_ONLY.getFlavor())));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule staticCompileRule2 =
        graphBuilder.getRule(cxxSourceRuleFactoryPDC.createCompileBuildTarget(genSourceName));
    assertNotNull(staticCompileRule2);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(staticCompileRule2)
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()),
        containsInAnyOrder(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor()),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PUBLIC, HeaderMode.SYMLINK_TREE_ONLY.getFlavor())));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    CxxSourceRuleFactory cxxSourceRuleFactoryPIC =
        CxxSourceRuleFactoryHelper.of(
            filesystem.getRootPath(), target, cxxPlatform, cxxBuckConfig, PicType.PIC);
    rule.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.SHARED, graphBuilder);
    BuildRule sharedRule =
        graphBuilder.getRule(
            CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
                target, cxxPlatform.getFlavor(), Linker.LinkType.SHARED));
    assertNotNull(sharedRule);
    assertEquals(
        ImmutableSet.of(
            sharedLibraryDepTarget,
            cxxSourceRuleFactoryPIC.createCompileBuildTarget("test/bar.cpp"),
            cxxSourceRuleFactoryPIC.createCompileBuildTarget(genSourceName)),
        sharedRule
            .getBuildDeps()
            .stream()
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule sharedCompileRule1 =
        graphBuilder.getRule(cxxSourceRuleFactoryPIC.createCompileBuildTarget("test/bar.cpp"));
    assertNotNull(sharedCompileRule1);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(sharedCompileRule1)
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()),
        containsInAnyOrder(
            genHeaderTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor()),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PUBLIC, HeaderMode.SYMLINK_TREE_ONLY.getFlavor())));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule sharedCompileRule2 =
        graphBuilder.getRule(cxxSourceRuleFactoryPIC.createCompileBuildTarget(genSourceName));
    assertNotNull(sharedCompileRule2);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(sharedCompileRule2)
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()),
        containsInAnyOrder(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor()),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PUBLIC, HeaderMode.SYMLINK_TREE_ONLY.getFlavor())));
  }

  @Test
  public void supportedPlatforms() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");

    // First, make sure without any platform regex, we get something back for each of the interface
    // methods.
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(target, cxxBuckConfig)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test.c"))));
    TargetGraph targetGraph1 = TargetGraphFactory.newInstance(cxxLibraryBuilder.build());
    ActionGraphBuilder builder1 = new TestActionGraphBuilder(targetGraph1);
    CxxLibrary cxxLibrary =
        (CxxLibrary) cxxLibraryBuilder.build(builder1, filesystem, targetGraph1);
    assertThat(
        cxxLibrary.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM, builder1).entrySet(),
        not(empty()));
    assertThat(
        cxxLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED, builder1)
            .getArgs(),
        not(empty()));

    // Now, verify we get nothing when the supported platform regex excludes our platform.
    cxxLibraryBuilder.setSupportedPlatformsRegex(Pattern.compile("nothing"));
    TargetGraph targetGraph2 = TargetGraphFactory.newInstance(cxxLibraryBuilder.build());
    ActionGraphBuilder builder2 = new TestActionGraphBuilder(targetGraph2);
    cxxLibrary = (CxxLibrary) cxxLibraryBuilder.build(builder2, filesystem, targetGraph2);
    assertThat(
        cxxLibrary.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM, builder2).entrySet(),
        empty());
    assertThat(
        cxxLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED, builder2)
            .getArgs(),
        empty());
  }

  @Test
  public void staticPicLibUsedForStaticPicLinkage() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of(filesystem, "test.cpp"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder.build(graphBuilder, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC_PIC, graphBuilder);
    Arg firstArg = nativeLinkableInput.getArgs().get(0);
    assertThat(firstArg, instanceOf(FileListableLinkerInputArg.class));
    ImmutableCollection<BuildRule> deps =
        BuildableSupport.getDepsCollection(firstArg, new SourcePathRuleFinder(graphBuilder));
    assertThat(deps.size(), is(1));
    BuildRule buildRule = deps.asList().get(0);
    assertThat(
        buildRule.getBuildTarget().getFlavors(), hasItem(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR));
  }

  @Test
  public void linkerFlagsLocationMacro() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target =
        BuildTargetFactory.newInstance("//:rule")
            .withFlavors(
                CxxDescriptionEnhancer.SHARED_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    GenruleBuilder depBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep")).setOut("out");
    CxxLibraryBuilder builder =
        new CxxLibraryBuilder(target, cxxBuckConfig)
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "--linker-script=%s", LocationMacro.of(depBuilder.getTarget()))))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(depBuilder.build(), builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Genrule dep = depBuilder.build(graphBuilder, filesystem, targetGraph);
    assertThat(builder.build().getExtraDeps(), hasItem(dep.getBuildTarget()));
    BuildRule binary = builder.build(graphBuilder, filesystem, targetGraph);
    assertThat(binary, instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs(), pathResolver),
        hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(binary.getBuildDeps(), hasItem(dep));
  }

  @Test
  public void locationMacroExpandedLinkerFlag() {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:bar")
            .withFlavors(
                CxxDescriptionEnhancer.SHARED_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = new ExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of(filesystem, "test.cpp"))));
    libBuilder.setLinkerFlags(
        ImmutableList.of(
            StringWithMacrosUtils.format("-Wl,--version-script=%s", LocationMacro.of(location))));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(libBuilder.build(), locBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    ExportFile loc = locBuilder.build(graphBuilder, filesystem, targetGraph);
    CxxLink lib = (CxxLink) libBuilder.build(graphBuilder, filesystem, targetGraph);

    assertThat(lib.getBuildDeps(), hasItem(loc));
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    assertThat(
        Arg.stringify(lib.getArgs(), pathResolver),
        hasItem(
            containsString(
                pathResolver
                    .getRelativePath(Preconditions.checkNotNull(loc.getSourcePathToOutput()))
                    .toString())));
  }

  @Test
  public void locationMacroExpandedPlatformLinkerFlagPlatformMatch() {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:bar")
            .withFlavors(
                CxxDescriptionEnhancer.SHARED_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = new ExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of(filesystem, "test.cpp"))));
    libBuilder.setPlatformLinkerFlags(
        PatternMatchedCollection.<ImmutableList<StringWithMacros>>builder()
            .add(
                Pattern.compile(CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().toString()),
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "-Wl,--version-script=%s", LocationMacro.of(location))))
            .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(libBuilder.build(), locBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    ExportFile loc = locBuilder.build(graphBuilder, filesystem, targetGraph);
    CxxLink lib = (CxxLink) libBuilder.build(graphBuilder, filesystem, targetGraph);

    assertThat(lib.getBuildDeps(), hasItem(loc));
    assertThat(
        Arg.stringify(lib.getArgs(), pathResolver),
        hasItem(
            String.format(
                "-Wl,--version-script=%s",
                pathResolver.getAbsolutePath(
                    Preconditions.checkNotNull(loc.getSourcePathToOutput())))));
    assertThat(
        Arg.stringify(lib.getArgs(), pathResolver),
        not(hasItem(pathResolver.getAbsolutePath(loc.getSourcePathToOutput()).toString())));
  }

  @Test
  public void locationMacroExpandedPlatformLinkerFlagNoPlatformMatch() {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:bar")
            .withFlavors(
                CxxDescriptionEnhancer.SHARED_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = new ExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of(filesystem, "test.cpp"))));
    libBuilder.setPlatformLinkerFlags(
        PatternMatchedCollection.<ImmutableList<StringWithMacros>>builder()
            .add(
                Pattern.compile("notarealplatform"),
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "-Wl,--version-script=%s", LocationMacro.of(location))))
            .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(libBuilder.build(), locBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    ExportFile loc = locBuilder.build(graphBuilder, filesystem, targetGraph);
    CxxLink lib = (CxxLink) libBuilder.build(graphBuilder, filesystem, targetGraph);

    assertThat(lib.getBuildDeps(), not(hasItem(loc)));
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    assertThat(
        Arg.stringify(lib.getArgs(), pathResolver),
        not(
            hasItem(
                containsString(
                    pathResolver
                        .getRelativePath(Preconditions.checkNotNull(loc.getSourcePathToOutput()))
                        .toString()))));
  }

  @Test
  public void locationMacroExpandedExportedLinkerFlag() {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = new ExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of(filesystem, "test.cpp"))));
    libBuilder.setExportedLinkerFlags(
        ImmutableList.of(
            StringWithMacrosUtils.format("-Wl,--version-script=%s", LocationMacro.of(location))));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(libBuilder.build(), locBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    ExportFile loc = locBuilder.build(graphBuilder, filesystem, targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder.build(graphBuilder, filesystem, targetGraph);

    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED, graphBuilder);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> BuildableSupport.getDepsCollection(arg, ruleFinder))
            .toSet(),
        hasItem(loc));
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs(), pathResolver),
        hasItem(
            containsString(
                pathResolver
                    .getRelativePath(Preconditions.checkNotNull(loc.getSourcePathToOutput()))
                    .toString())));
  }

  @Test
  public void locationMacroExpandedExportedPlatformLinkerFlagPlatformMatch() {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = new ExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of(filesystem, "test.cpp"))));
    libBuilder.setExportedPlatformLinkerFlags(
        PatternMatchedCollection.<ImmutableList<StringWithMacros>>builder()
            .add(
                Pattern.compile(CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().toString()),
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "-Wl,--version-script=%s", LocationMacro.of(location))))
            .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(libBuilder.build(), locBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    ExportFile loc = locBuilder.build(graphBuilder, filesystem, targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder.build(graphBuilder, filesystem, targetGraph);

    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED, graphBuilder);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> BuildableSupport.getDepsCollection(arg, ruleFinder))
            .toSet(),
        hasItem(loc));
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs(), pathResolver),
        hasItem(
            containsString(
                pathResolver
                    .getRelativePath(Preconditions.checkNotNull(loc.getSourcePathToOutput()))
                    .toString())));
  }

  @Test
  public void locationMacroExpandedExportedPlatformLinkerFlagNoPlatformMatch() {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:bar")
            .withFlavors(CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = new ExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of(filesystem, "test.cpp"))));
    libBuilder.setExportedPlatformLinkerFlags(
        PatternMatchedCollection.<ImmutableList<StringWithMacros>>builder()
            .add(
                Pattern.compile("notarealplatform"),
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "-Wl,--version-script=%s", LocationMacro.of(location))))
            .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(libBuilder.build(), locBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    ExportFile loc = locBuilder.build(graphBuilder, filesystem, targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder.build(graphBuilder, filesystem, targetGraph);

    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED, graphBuilder);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> BuildableSupport.getDepsCollection(arg, ruleFinder))
            .toSet(),
        not(hasItem(loc)));
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs(), pathResolver),
        not(
            hasItem(
                containsString(
                    pathResolver
                        .getRelativePath(Preconditions.checkNotNull(loc.getSourcePathToOutput()))
                        .toString()))));
  }

  @Test
  public void libraryWithoutSourcesDoesntHaveOutput() {
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:bar")
            .withFlavors(
                CxxDescriptionEnhancer.STATIC_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    BuildRule lib = libBuilder.build(graphBuilder, filesystem, targetGraph);

    assertThat(lib.getSourcePathToOutput(), nullValue());
  }

  @Test
  public void libraryWithoutSourcesDoesntBuildAnything() {
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:bar")
            .withFlavors(
                CxxDescriptionEnhancer.STATIC_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    BuildRule lib = libBuilder.build(graphBuilder, filesystem, targetGraph);

    assertThat(lib.getBuildDeps(), is(empty()));
    assertThat(
        lib.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext()), is(empty()));
  }

  @Test
  public void nativeLinkableDeps() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    CxxLibrary dep =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"), cxxBuckConfig)
                .build(graphBuilder);
    CxxLibrary rule =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
                .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
                .build(graphBuilder);
    assertThat(
        rule.getNativeLinkableDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder),
        Matchers.contains(dep));
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableExportedDepsForPlatform(
                CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)),
        Matchers.<NativeLinkable>empty());
  }

  @Test
  public void nativeLinkableExportedDeps() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    CxxLibrary dep =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"), cxxBuckConfig)
                .build(graphBuilder);
    CxxLibrary rule =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
                .setExportedDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
                .build(graphBuilder);
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)),
        empty());
    assertThat(
        rule.getNativeLinkableExportedDepsForPlatform(
            CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder),
        Matchers.contains(dep));
  }

  @Test
  public void nativeLinkTargetMode() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    CxxLibrary rule =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
                .setSoname("libsoname.so")
                .build(graphBuilder);
    assertThat(
        rule.getNativeLinkTargetMode(CxxPlatformUtils.DEFAULT_PLATFORM),
        equalTo(NativeLinkTargetMode.library("libsoname.so")));
  }

  @Test
  public void nativeLinkTargetDeps() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    CxxLibrary dep =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"), cxxBuckConfig)
                .build(graphBuilder);
    CxxLibrary exportedDep =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:exported_dep"), cxxBuckConfig)
                .build(graphBuilder);
    CxxLibrary rule =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
                .setExportedDeps(
                    ImmutableSortedSet.of(dep.getBuildTarget(), exportedDep.getBuildTarget()))
                .build(graphBuilder);
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkTargetDeps(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)),
        hasItems(dep, exportedDep));
  }

  @Test
  public void nativeLinkTargetInput() {
    CxxLibraryBuilder ruleBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
            .setLinkerFlags(ImmutableList.of(StringWithMacrosUtils.format("--flag")))
            .setExportedLinkerFlags(
                ImmutableList.of(StringWithMacrosUtils.format("--exported-flag")));
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(ruleBuilder.build()));
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    CxxLibrary rule = (CxxLibrary) ruleBuilder.build(graphBuilder);
    NativeLinkableInput input =
        rule.getNativeLinkTargetInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, pathResolver, ruleFinder);
    assertThat(Arg.stringify(input.getArgs(), pathResolver), hasItems("--flag", "--exported-flag"));
  }

  @Test
  public void exportedDepsArePropagatedToRuntimeDeps() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxBinaryBuilder cxxBinaryBuilder =
        new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"));
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"), cxxBuckConfig)
            .setExportedDeps(ImmutableSortedSet.of(cxxBinaryBuilder.getTarget()));
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(cxxLibraryBuilder.build(), cxxBinaryBuilder.build()));
    cxxBinaryBuilder.build(graphBuilder, filesystem);
    CxxLibrary cxxLibrary = (CxxLibrary) cxxLibraryBuilder.build(graphBuilder, filesystem);
    assertThat(
        cxxLibrary
            .getRuntimeDeps(new SourcePathRuleFinder(graphBuilder))
            .collect(ImmutableSet.toImmutableSet()),
        hasItem(cxxBinaryBuilder.getTarget()));
  }

  @Test
  public void sharedLibraryShouldLinkOwnRequiredLibraries() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxPlatformUtils.DEFAULT_PLATFORM;

    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(
            BuildTargetFactory.newInstance("//:foo")
                .withFlavors(platform.getFlavor(), CxxDescriptionEnhancer.SHARED_FLAVOR),
            cxxBuckConfig);
    libraryBuilder
        .setLibraries(
            ImmutableSortedSet.of(
                FrameworkPath.ofSourceTreePath(
                    new SourceTreePath(
                        PBXReference.SourceTree.SDKROOT,
                        Paths.get("/usr/lib/libz.dylib"),
                        Optional.empty())),
                FrameworkPath.ofSourcePath(FakeSourcePath.of("/another/path/liba.dylib"))))
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    CxxLink library = (CxxLink) libraryBuilder.build(graphBuilder, filesystem, targetGraph);
    assertThat(
        Arg.stringify(library.getArgs(), pathResolver),
        hasItems("-L", "/another/path", "$SDKROOT/usr/lib", "-la", "-lz"));
  }

  @Test
  public void sharedLibraryShouldLinkOwnRequiredLibrariesForCxxLibrary() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxPlatformUtils.DEFAULT_PLATFORM;

    ImmutableSortedSet<FrameworkPath> libraries =
        ImmutableSortedSet.of(
            FrameworkPath.ofSourcePath(FakeSourcePath.of("/some/path/libs.dylib")),
            FrameworkPath.ofSourcePath(FakeSourcePath.of("/another/path/liba.dylib")));

    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:foo"), cxxBuckConfig);
    libraryBuilder
        .setLibraries(libraries)
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    CxxLibrary library = (CxxLibrary) libraryBuilder.build(graphBuilder, filesystem, targetGraph);
    assertThat(
        library
            .getNativeLinkTargetInput(platform, graphBuilder, pathResolver, ruleFinder)
            .getLibraries(),
        equalTo(libraries));
  }

  @Test
  public void ruleWithoutHeadersDoesNotUseSymlinkTree() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(cxxLibraryBuilder.build()));
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder.build(graphBuilder, filesystem);
    CxxPreprocessorInput input =
        rule.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder);
    assertThat(getHeaderNames(input.getIncludes()), empty());
    assertThat(ImmutableSortedSet.copyOf(input.getDeps(graphBuilder, ruleFinder)), empty());
  }

  @Test
  public void thinArchiveSettingIsPropagatedToArchive() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget target =
        BuildTargetFactory.newInstance("//:rule")
            .withFlavors(
                CxxDescriptionEnhancer.STATIC_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    CxxLibraryBuilder libBuilder =
        new CxxLibraryBuilder(
            target,
            new CxxBuckConfig(
                FakeBuckConfig.builder()
                    .setSections(
                        "[cxx]",
                        "archive_contents=thin",
                        "sandbox_sources=" + cxxBuckConfig.sandboxSources())
                    .build()),
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of(filesystem, "test.cpp"))));
    Archive lib = (Archive) libBuilder.build(graphBuilder, filesystem, targetGraph);
    assertThat(lib.getContents(), equalTo(ArchiveContents.THIN));
  }

  @Test
  public void forceStatic() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
            .setPreferredLinkage(NativeLinkable.Linkage.STATIC);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(cxxLibraryBuilder.build()));
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder.build(graphBuilder, filesystem);
    assertThat(
        rule.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder),
        equalTo(NativeLinkable.Linkage.STATIC));
  }

  @Test
  public void srcsFromCxxGenrule() {
    CxxGenruleBuilder srcBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:src")).setOut("foo.cpp");
    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(DefaultBuildTargetSourcePath.of(srcBuilder.getTarget()))));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(srcBuilder.build(), libraryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    graphBuilder.requireRule(
        libraryBuilder
            .getTarget()
            .withAppendedFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                CxxLibraryDescription.Type.STATIC.getFlavor()));
    verifySourcePaths(graphBuilder);
  }

  @Test
  public void headersFromCxxGenrule() {
    CxxGenruleBuilder srcBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:src")).setOut("foo.h");
    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.cpp"))))
            .setHeaders(
                ImmutableSortedSet.of(DefaultBuildTargetSourcePath.of(srcBuilder.getTarget())));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(srcBuilder.build(), libraryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    graphBuilder.requireRule(
        libraryBuilder
            .getTarget()
            .withAppendedFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                CxxLibraryDescription.Type.STATIC.getFlavor()));
    verifySourcePaths(graphBuilder);
  }

  @Test
  public void locationMacroFromCxxGenrule() {
    CxxGenruleBuilder srcBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:src")).setOut("linker.script");
    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.cpp"))))
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format("%s", LocationMacro.of(srcBuilder.getTarget()))));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(srcBuilder.build(), libraryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    graphBuilder.requireRule(
        libraryBuilder
            .getTarget()
            .withAppendedFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                CxxLibraryDescription.Type.SHARED.getFlavor()));
    verifySourcePaths(graphBuilder);
  }

  @Test
  public void platformDeps() {
    CxxLibraryBuilder depABuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep_a"), cxxBuckConfig);
    CxxLibraryBuilder depBBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep_b"), cxxBuckConfig);
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
            .setExportedPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().toString()),
                        ImmutableSortedSet.of(depABuilder.getTarget()))
                    .add(Pattern.compile("other"), ImmutableSortedSet.of(depBBuilder.getTarget()))
                    .build());
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                depABuilder.build(), depBBuilder.build(), cxxLibraryBuilder.build()));
    graphBuilder.requireRule(depABuilder.getTarget());
    graphBuilder.requireRule(depBBuilder.getTarget());
    CxxLibrary rule = (CxxLibrary) graphBuilder.requireRule(cxxLibraryBuilder.getTarget());
    assertThat(
        rule.getNativeLinkableExportedDepsForPlatform(
            CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder),
        Matchers.contains(graphBuilder.requireRule(depABuilder.getTarget())));
    assertThat(
        rule.getCxxPreprocessorDeps(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder),
        Matchers.contains(graphBuilder.requireRule(depABuilder.getTarget())));
  }

  @Test
  public void inferCaptureAllIncludesExportedDeps() {
    CxxLibraryBuilder exportedDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:exported_dep"), cxxBuckConfig)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("dep.c"))));
    CxxLibraryBuilder ruleBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
            .setExportedDeps(ImmutableSortedSet.of(exportedDepBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(exportedDepBuilder.build(), ruleBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    BuildRule rule =
        graphBuilder.requireRule(
            ruleBuilder
                .getTarget()
                .withFlavors(
                    CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor(),
                    CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor()));
    assertThat(
        RichStream.from(rule.getBuildDeps())
            .map(BuildRule::getBuildTarget)
            .map(t -> t.withFlavors())
            .toImmutableSet(),
        hasItem(exportedDepBuilder.getTarget()));
  }

  /**
   * Verify that all source paths are resolvable, which wouldn't be the case if `cxx_genrule`
   * outputs were not handled correctly.
   */
  private void verifySourcePaths(ActionGraphBuilder graphBuilder) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildContext buildContext = FakeBuildContext.withSourcePathResolver(pathResolver);
    BuildableContext buildableContext = new FakeBuildableContext();
    for (BuildRule rule : graphBuilder.getBuildRules()) {
      rule.getBuildSteps(buildContext, buildableContext);
    }
  }
}
