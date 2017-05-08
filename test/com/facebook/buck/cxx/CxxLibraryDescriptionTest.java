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
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.DependencyAggregationTestUtil;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
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
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.MoreCollectors;
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
          new ExplicitBuildTargetSourcePath(
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
  public void createBuildRule() throws Exception {
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
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    CxxLibraryBuilder depBuilder =
        new CxxLibraryBuilder(depTarget, cxxBuckConfig)
            .setExportedHeaders(
                SourceList.ofUnnamedSources(ImmutableSortedSet.of(new FakeSourcePath("blah.h"))))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.cpp"))));
    BuildTarget headerSymlinkTreeTarget =
        BuildTarget.builder(depTarget)
            .addFlavors(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
            .addFlavors(CxxPreprocessables.HeaderMode.SYMLINK_TREE_ONLY.getFlavor())
            .build();

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
                    new FakeSourcePath(headerName),
                    new DefaultBuildTargetSourcePath(genHeaderTarget)))
            .setHeaders(ImmutableSortedSet.of(new FakeSourcePath(privateHeaderName)))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("test/bar.cpp")),
                    SourceWithFlags.of(new DefaultBuildTargetSourcePath(genSourceTarget))))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourcePath(new FakeSourcePath("/some/framework/path/s.dylib")),
                    FrameworkPath.ofSourcePath(
                        new FakeSourcePath("/another/framework/path/a.dylib"))))
            .setDeps(ImmutableSortedSet.of(depTarget));

    // Build the target graph.
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            genHeaderBuilder.build(),
            genSourceBuilder.build(),
            depBuilder.build(),
            cxxLibraryBuilder.build());

    // Build the rules.
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    genHeaderBuilder.build(resolver, filesystem, targetGraph);
    genSourceBuilder.build(resolver, filesystem, targetGraph);
    depBuilder.build(resolver, filesystem, targetGraph);
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder.build(resolver, filesystem, targetGraph);

    // Verify public preprocessor input.
    CxxPreprocessorInput publicInput =
        rule.getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PUBLIC);
    assertThat(
        publicInput.getFrameworks(),
        containsInAnyOrder(
            FrameworkPath.ofSourcePath(
                new PathSourcePath(filesystem, Paths.get("/some/framework/path/s.dylib"))),
            FrameworkPath.ofSourcePath(
                new PathSourcePath(filesystem, Paths.get("/another/framework/path/a.dylib")))));
    CxxSymlinkTreeHeaders publicHeaders = (CxxSymlinkTreeHeaders) publicInput.getIncludes().get(0);
    assertThat(publicHeaders.getIncludeType(), equalTo(CxxPreprocessables.IncludeType.LOCAL));
    assertThat(
        publicHeaders.getNameToPathMap(),
        equalTo(
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(headerName),
                new FakeSourcePath(headerName),
                Paths.get(genHeaderName),
                new DefaultBuildTargetSourcePath(genHeaderTarget))));
    assertThat(
        publicHeaders.getHeaderMap(),
        equalTo(getHeaderMaps(filesystem, target, resolver, cxxPlatform, HeaderVisibility.PUBLIC)));

    // Verify private preprocessor input.
    CxxPreprocessorInput privateInput =
        rule.getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PRIVATE);
    assertThat(
        privateInput.getFrameworks(),
        containsInAnyOrder(
            FrameworkPath.ofSourcePath(
                new PathSourcePath(filesystem, Paths.get("/some/framework/path/s.dylib"))),
            FrameworkPath.ofSourcePath(
                new PathSourcePath(filesystem, Paths.get("/another/framework/path/a.dylib")))));
    CxxSymlinkTreeHeaders privateHeaders =
        (CxxSymlinkTreeHeaders) privateInput.getIncludes().get(0);
    assertThat(privateHeaders.getIncludeType(), equalTo(CxxPreprocessables.IncludeType.LOCAL));
    assertThat(
        privateHeaders.getNameToPathMap(),
        equalTo(
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(privateHeaderName), new FakeSourcePath(privateHeaderName))));
    assertThat(
        privateHeaders.getHeaderMap(),
        equalTo(
            getHeaderMaps(filesystem, target, resolver, cxxPlatform, HeaderVisibility.PRIVATE)));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC);
    BuildRule archiveRule =
        resolver.getRule(
            CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
                target, cxxPlatform.getFlavor(), CxxSourceRuleFactory.PicType.PDC));
    assertNotNull(archiveRule);
    assertEquals(
        ImmutableSet.of(
            cxxSourceRuleFactory.createCompileBuildTarget("test/bar.cpp"),
            cxxSourceRuleFactory.createCompileBuildTarget(genSourceName)),
        archiveRule
            .getBuildDeps()
            .stream()
            .map(BuildRule::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule compileRule1 =
        resolver.getRule(cxxSourceRuleFactory.createCompileBuildTarget("test/bar.cpp"));
    assertNotNull(compileRule1);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(compileRule1)
            .map(BuildRule::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()),
        containsInAnyOrder(
            genHeaderTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor()),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                HeaderVisibility.PUBLIC,
                CxxPreprocessables.HeaderMode.SYMLINK_TREE_ONLY.getFlavor())));

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule compileRule2 =
        resolver.getRule(cxxSourceRuleFactory.createCompileBuildTarget(genSourceName));
    assertNotNull(compileRule2);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(compileRule2)
            .map(BuildRule::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()),
        containsInAnyOrder(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor()),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                HeaderVisibility.PUBLIC,
                CxxPreprocessables.HeaderMode.SYMLINK_TREE_ONLY.getFlavor())));
  }

  @Test
  public void overrideSoname() throws Exception {

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
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.cpp"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(ruleBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    CxxLink rule = (CxxLink) ruleBuilder.build(resolver, filesystem, targetGraph);
    Linker linker = cxxPlatform.getLd().resolve(resolver);
    ImmutableList<String> sonameArgs = ImmutableList.copyOf(linker.soname(soname));
    assertThat(
        Arg.stringify(rule.getArgs(), pathResolver),
        hasItems(sonameArgs.toArray(new String[sonameArgs.size()])));
  }

  @Test
  public void linkWhole() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;

    // Setup the target name and build params.
    BuildTarget target = BuildTargetFactory.newInstance("//:test");

    // First, create a cxx library without using link whole.
    CxxLibraryBuilder normalBuilder =
        new CxxLibraryBuilder(target, cxxBuckConfig)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.cpp"))));
    TargetGraph normalGraph = TargetGraphFactory.newInstance(normalBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(normalGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    CxxLibrary normal = (CxxLibrary) normalBuilder.build(resolver, filesystem, normalGraph);

    // Lookup the link whole flags.
    Linker linker = cxxPlatform.getLd().resolve(resolver);
    ImmutableList<String> linkWholeFlags =
        FluentIterable.from(linker.linkWhole(StringArg.of("sentinel")))
            .transformAndConcat((input1) -> Arg.stringifyList(input1, pathResolver))
            .filter(Predicates.not("sentinel"::equals))
            .toList();

    // Verify that the linker args contains the link whole flags.
    NativeLinkableInput input =
        normal.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC);
    assertThat(
        Arg.stringify(input.getArgs(), pathResolver),
        not(hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()]))));

    // Create a cxx library using link whole.
    CxxLibraryBuilder linkWholeBuilder =
        new CxxLibraryBuilder(target, cxxBuckConfig)
            .setLinkWhole(true)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.cpp"))));

    TargetGraph linkWholeGraph = TargetGraphFactory.newInstance(linkWholeBuilder.build());
    resolver = new BuildRuleResolver(normalGraph, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary linkWhole =
        (CxxLibrary)
            linkWholeBuilder
                .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.cpp"))))
                .build(resolver, filesystem, linkWholeGraph);

    // Verify that the linker args contains the link whole flags.
    NativeLinkableInput linkWholeInput =
        linkWhole.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC);
    assertThat(
        Arg.stringify(linkWholeInput.getArgs(), pathResolver),
        hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()])));
  }

  @Test
  public void createCxxLibraryBuildRules() throws Exception {
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
                SourceList.ofUnnamedSources(ImmutableSortedSet.of(new FakeSourcePath("blah.h"))))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.cpp"))));
    BuildTarget sharedLibraryDepTarget =
        BuildTarget.builder(depTarget)
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .addFlavors(cxxPlatform.getFlavor())
            .build();
    BuildTarget headerSymlinkTreeTarget =
        BuildTarget.builder(depTarget)
            .addFlavors(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
            .addFlavors(CxxPreprocessables.HeaderMode.SYMLINK_TREE_ONLY.getFlavor())
            .build();

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxSourceRuleFactory cxxSourceRuleFactoryPDC =
        CxxSourceRuleFactoryHelper.of(
            filesystem.getRootPath(),
            target,
            cxxPlatform,
            cxxBuckConfig,
            CxxSourceRuleFactory.PicType.PDC);

    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(target, cxxBuckConfig)
            .setExportedHeaders(
                ImmutableSortedMap.of(
                    genHeaderName, new DefaultBuildTargetSourcePath(genHeaderTarget)))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath(sourceName)),
                    SourceWithFlags.of(new DefaultBuildTargetSourcePath(genSourceTarget))))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourcePath(new FakeSourcePath("/some/framework/path/s.dylib")),
                    FrameworkPath.ofSourcePath(
                        new FakeSourcePath("/another/framework/path/a.dylib"))))
            .setDeps(ImmutableSortedSet.of(depTarget));

    // Build target graph.
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            genHeaderBuilder.build(),
            genSourceBuilder.build(),
            depBuilder.build(),
            cxxLibraryBuilder.build());

    // Construct C/C++ library build rules.
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    genHeaderBuilder.build(resolver, filesystem, targetGraph);
    genSourceBuilder.build(resolver, filesystem, targetGraph);
    depBuilder.build(resolver, filesystem, targetGraph);
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder.build(resolver, filesystem, targetGraph);

    // Verify the C/C++ preprocessor input is setup correctly.
    CxxPreprocessorInput publicInput =
        rule.getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PUBLIC);
    assertThat(
        publicInput.getFrameworks(),
        containsInAnyOrder(
            FrameworkPath.ofSourcePath(
                new PathSourcePath(filesystem, Paths.get("/some/framework/path/s.dylib"))),
            FrameworkPath.ofSourcePath(
                new PathSourcePath(filesystem, Paths.get("/another/framework/path/a.dylib")))));
    CxxSymlinkTreeHeaders publicHeaders = (CxxSymlinkTreeHeaders) publicInput.getIncludes().get(0);
    assertThat(publicHeaders.getIncludeType(), equalTo(CxxPreprocessables.IncludeType.LOCAL));
    assertThat(
        publicHeaders.getNameToPathMap(),
        equalTo(
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(genHeaderName), new DefaultBuildTargetSourcePath(genHeaderTarget))));
    assertThat(
        publicHeaders.getHeaderMap(),
        equalTo(getHeaderMaps(filesystem, target, resolver, cxxPlatform, HeaderVisibility.PUBLIC)));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC);
    BuildRule staticRule =
        resolver.getRule(
            CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
                target, cxxPlatform.getFlavor(), CxxSourceRuleFactory.PicType.PDC));
    assertNotNull(staticRule);
    assertEquals(
        ImmutableSet.of(
            cxxSourceRuleFactoryPDC.createCompileBuildTarget("test/bar.cpp"),
            cxxSourceRuleFactoryPDC.createCompileBuildTarget(genSourceName)),
        staticRule
            .getBuildDeps()
            .stream()
            .map(BuildRule::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule staticCompileRule1 =
        resolver.getRule(cxxSourceRuleFactoryPDC.createCompileBuildTarget("test/bar.cpp"));
    assertNotNull(staticCompileRule1);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(staticCompileRule1)
            .map(BuildRule::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()),
        containsInAnyOrder(
            genHeaderTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor()),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                HeaderVisibility.PUBLIC,
                CxxPreprocessables.HeaderMode.SYMLINK_TREE_ONLY.getFlavor())));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule staticCompileRule2 =
        resolver.getRule(cxxSourceRuleFactoryPDC.createCompileBuildTarget(genSourceName));
    assertNotNull(staticCompileRule2);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(staticCompileRule2)
            .map(BuildRule::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()),
        containsInAnyOrder(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor()),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                HeaderVisibility.PUBLIC,
                CxxPreprocessables.HeaderMode.SYMLINK_TREE_ONLY.getFlavor())));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    CxxSourceRuleFactory cxxSourceRuleFactoryPIC =
        CxxSourceRuleFactoryHelper.of(
            filesystem.getRootPath(),
            target,
            cxxPlatform,
            cxxBuckConfig,
            CxxSourceRuleFactory.PicType.PIC);
    rule.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.SHARED);
    BuildRule sharedRule =
        resolver.getRule(
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
            .collect(MoreCollectors.toImmutableSet()));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule sharedCompileRule1 =
        resolver.getRule(cxxSourceRuleFactoryPIC.createCompileBuildTarget("test/bar.cpp"));
    assertNotNull(sharedCompileRule1);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(sharedCompileRule1)
            .map(BuildRule::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()),
        containsInAnyOrder(
            genHeaderTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor()),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                HeaderVisibility.PUBLIC,
                CxxPreprocessables.HeaderMode.SYMLINK_TREE_ONLY.getFlavor())));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule sharedCompileRule2 =
        resolver.getRule(cxxSourceRuleFactoryPIC.createCompileBuildTarget(genSourceName));
    assertNotNull(sharedCompileRule2);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(sharedCompileRule2)
            .map(BuildRule::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()),
        containsInAnyOrder(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor()),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                HeaderVisibility.PUBLIC,
                CxxPreprocessables.HeaderMode.SYMLINK_TREE_ONLY.getFlavor())));
  }

  @Test
  public void supportedPlatforms() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");

    // First, make sure without any platform regex, we get something back for each of the interface
    // methods.
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(target, cxxBuckConfig)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.c"))));
    TargetGraph targetGraph1 = TargetGraphFactory.newInstance(cxxLibraryBuilder.build());
    BuildRuleResolver resolver1 =
        new BuildRuleResolver(targetGraph1, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary cxxLibrary =
        (CxxLibrary) cxxLibraryBuilder.build(resolver1, filesystem, targetGraph1);
    assertThat(
        cxxLibrary.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM).entrySet(), not(empty()));
    assertThat(
        cxxLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED)
            .getArgs(),
        not(empty()));

    // Now, verify we get nothing when the supported platform regex excludes our platform.
    cxxLibraryBuilder.setSupportedPlatformsRegex(Pattern.compile("nothing"));
    TargetGraph targetGraph2 = TargetGraphFactory.newInstance(cxxLibraryBuilder.build());
    BuildRuleResolver resolver2 =
        new BuildRuleResolver(targetGraph2, new DefaultTargetNodeToBuildRuleTransformer());
    cxxLibrary = (CxxLibrary) cxxLibraryBuilder.build(resolver2, filesystem, targetGraph2);
    assertThat(
        cxxLibrary.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM).entrySet(), empty());
    assertThat(
        cxxLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED)
            .getArgs(),
        empty());
  }

  @Test
  public void staticPicLibUsedForStaticPicLinkage() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary lib = (CxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC_PIC);
    Arg firstArg = nativeLinkableInput.getArgs().get(0);
    assertThat(firstArg, instanceOf(FileListableLinkerInputArg.class));
    ImmutableCollection<BuildRule> deps = firstArg.getDeps(new SourcePathRuleFinder(resolver));
    assertThat(deps.size(), is(1));
    BuildRule buildRule = deps.asList().get(0);
    assertThat(
        buildRule.getBuildTarget().getFlavors(), hasItem(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR));
  }

  @Test
  public void linkerFlagsLocationMacro() throws Exception {
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
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(depBuilder.build(), builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    Genrule dep = depBuilder.build(resolver, filesystem, targetGraph);
    assertThat(builder.build().getExtraDeps(), hasItem(dep.getBuildTarget()));
    BuildRule binary = builder.build(resolver, filesystem, targetGraph);
    assertThat(binary, instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs(), pathResolver),
        hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath(pathResolver))));
    assertThat(binary.getBuildDeps(), hasItem(dep));
  }

  @Test
  public void locationMacroExpandedLinkerFlag() throws Exception {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:bar")
            .withFlavors(
                CxxDescriptionEnhancer.SHARED_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setLinkerFlags(
        ImmutableList.of(
            StringWithMacrosUtils.format("-Wl,--version-script=%s", LocationMacro.of(location))));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(libBuilder.build(), locBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ExportFile loc = locBuilder.build(resolver, filesystem, targetGraph);
    CxxLink lib = (CxxLink) libBuilder.build(resolver, filesystem, targetGraph);

    assertThat(lib.getBuildDeps(), hasItem(loc));
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    assertThat(
        Arg.stringify(lib.getArgs(), pathResolver),
        hasItem(
            containsString(
                pathResolver
                    .getRelativePath(Preconditions.checkNotNull(loc.getSourcePathToOutput()))
                    .toString())));
  }

  @Test
  public void locationMacroExpandedPlatformLinkerFlagPlatformMatch() throws Exception {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:bar")
            .withFlavors(
                CxxDescriptionEnhancer.SHARED_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    ExportFile loc = locBuilder.build(resolver, filesystem, targetGraph);
    CxxLink lib = (CxxLink) libBuilder.build(resolver, filesystem, targetGraph);

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
  public void locationMacroExpandedPlatformLinkerFlagNoPlatformMatch() throws Exception {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:bar")
            .withFlavors(
                CxxDescriptionEnhancer.SHARED_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ExportFile loc = locBuilder.build(resolver, filesystem, targetGraph);
    CxxLink lib = (CxxLink) libBuilder.build(resolver, filesystem, targetGraph);

    assertThat(lib.getBuildDeps(), not(hasItem(loc)));
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
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
  public void locationMacroExpandedExportedLinkerFlag() throws Exception {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setExportedLinkerFlags(
        ImmutableList.of(
            StringWithMacrosUtils.format("-Wl,--version-script=%s", LocationMacro.of(location))));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(libBuilder.build(), locBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ExportFile loc = locBuilder.build(resolver, filesystem, targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> arg.getDeps(ruleFinder))
            .toSet(),
        hasItem(loc));
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs(), pathResolver),
        hasItem(
            containsString(
                pathResolver
                    .getRelativePath(Preconditions.checkNotNull(loc.getSourcePathToOutput()))
                    .toString())));
  }

  @Test
  public void locationMacroExpandedExportedPlatformLinkerFlagPlatformMatch() throws Exception {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ExportFile loc = locBuilder.build(resolver, filesystem, targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> arg.getDeps(ruleFinder))
            .toSet(),
        hasItem(loc));
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs(), pathResolver),
        hasItem(
            containsString(
                pathResolver
                    .getRelativePath(Preconditions.checkNotNull(loc.getSourcePathToOutput()))
                    .toString())));
  }

  @Test
  public void locationMacroExpandedExportedPlatformLinkerFlagNoPlatformMatch() throws Exception {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:bar")
            .withFlavors(CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ExportFile loc = locBuilder.build(resolver, filesystem, targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder.build(resolver, filesystem, targetGraph);

    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> arg.getDeps(ruleFinder))
            .toSet(),
        not(hasItem(loc)));
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
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
  public void libraryWithoutSourcesDoesntHaveOutput() throws Exception {
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:bar")
            .withFlavors(
                CxxDescriptionEnhancer.STATIC_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule lib = libBuilder.build(resolver, filesystem, targetGraph);

    assertThat(lib.getSourcePathToOutput(), nullValue());
  }

  @Test
  public void libraryWithoutSourcesDoesntBuildAnything() throws Exception {
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:bar")
            .withFlavors(
                CxxDescriptionEnhancer.STATIC_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target, cxxBuckConfig);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule lib = libBuilder.build(resolver, filesystem, targetGraph);

    assertThat(lib.getBuildDeps(), is(empty()));
    assertThat(
        lib.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext()), is(empty()));
  }

  @Test
  public void nativeLinkableDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary dep =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"), cxxBuckConfig)
                .build(resolver);
    CxxLibrary rule =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
                .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
                .build(resolver);
    assertThat(
        rule.getNativeLinkableDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.contains(dep));
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableExportedDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)),
        Matchers.<NativeLinkable>empty());
  }

  @Test
  public void nativeLinkableExportedDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary dep =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"), cxxBuckConfig)
                .build(resolver);
    CxxLibrary rule =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
                .setExportedDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
                .build(resolver);
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)),
        empty());
    assertThat(
        rule.getNativeLinkableExportedDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.contains(dep));
  }

  @Test
  public void nativeLinkTargetMode() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary rule =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
                .setSoname("libsoname.so")
                .build(resolver);
    assertThat(
        rule.getNativeLinkTargetMode(CxxPlatformUtils.DEFAULT_PLATFORM),
        equalTo(NativeLinkTargetMode.library("libsoname.so")));
  }

  @Test
  public void nativeLinkTargetDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary dep =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"), cxxBuckConfig)
                .build(resolver);
    CxxLibrary exportedDep =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:exported_dep"), cxxBuckConfig)
                .build(resolver);
    CxxLibrary rule =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
                .setExportedDeps(
                    ImmutableSortedSet.of(dep.getBuildTarget(), exportedDep.getBuildTarget()))
                .build(resolver);
    assertThat(
        ImmutableList.copyOf(rule.getNativeLinkTargetDeps(CxxPlatformUtils.DEFAULT_PLATFORM)),
        hasItems(dep, exportedDep));
  }

  @Test
  public void nativeLinkTargetInput() throws Exception {
    CxxLibraryBuilder ruleBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
            .setLinkerFlags(ImmutableList.of(StringWithMacrosUtils.format("--flag")))
            .setExportedLinkerFlags(
                ImmutableList.of(StringWithMacrosUtils.format("--exported-flag")));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(ruleBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    CxxLibrary rule = (CxxLibrary) ruleBuilder.build(resolver);
    NativeLinkableInput input = rule.getNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(Arg.stringify(input.getArgs(), pathResolver), hasItems("--flag", "--exported-flag"));
  }

  @Test
  public void exportedDepsArePropagatedToRuntimeDeps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxBinaryBuilder cxxBinaryBuilder =
        new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"));
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"), cxxBuckConfig)
            .setExportedDeps(ImmutableSortedSet.of(cxxBinaryBuilder.getTarget()));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(cxxLibraryBuilder.build(), cxxBinaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    cxxBinaryBuilder.build(resolver, filesystem);
    CxxLibrary cxxLibrary = (CxxLibrary) cxxLibraryBuilder.build(resolver, filesystem);
    assertThat(
        cxxLibrary.getRuntimeDeps().collect(MoreCollectors.toImmutableSet()),
        hasItem(cxxBinaryBuilder.getTarget()));
  }

  @Test
  public void sharedLibraryShouldLinkOwnRequiredLibraries() throws Exception {
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
                FrameworkPath.ofSourcePath(new FakeSourcePath("/another/path/liba.dylib"))))
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    CxxLink library = (CxxLink) libraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        Arg.stringify(library.getArgs(), pathResolver),
        hasItems("-L", "/another/path", "$SDKROOT/usr/lib", "-la", "-lz"));
  }

  @Test
  public void sharedLibraryShouldLinkOwnRequiredLibrariesForCxxLibrary() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxPlatformUtils.DEFAULT_PLATFORM;

    ImmutableSortedSet<FrameworkPath> libraries =
        ImmutableSortedSet.of(
            FrameworkPath.ofSourcePath(new FakeSourcePath("/some/path/libs.dylib")),
            FrameworkPath.ofSourcePath(new FakeSourcePath("/another/path/liba.dylib")));

    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:foo"), cxxBuckConfig);
    libraryBuilder
        .setLibraries(libraries)
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary library = (CxxLibrary) libraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(library.getNativeLinkTargetInput(platform).getLibraries(), equalTo(libraries));
  }

  @Test
  public void ruleWithoutHeadersDoesNotUseSymlinkTree() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig);
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(cxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder.build(resolver, filesystem);
    CxxPreprocessorInput input =
        rule.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, HeaderVisibility.PUBLIC);
    assertThat(getHeaderNames(input.getIncludes()), empty());
    assertThat(ImmutableSortedSet.copyOf(input.getDeps(resolver, ruleFinder)), empty());
  }

  @Test
  public void thinArchiveSettingIsPropagatedToArchive() throws Exception {
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    Archive lib = (Archive) libBuilder.build(resolver, filesystem, targetGraph);
    assertThat(lib.getContents(), equalTo(Archive.Contents.THIN));
  }

  @Test
  public void forceStatic() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
            .setPreferredLinkage(NativeLinkable.Linkage.STATIC);
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(cxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder.build(resolver, filesystem);
    assertThat(
        rule.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        equalTo(NativeLinkable.Linkage.STATIC));
  }

  @Test
  public void srcsFromCxxGenrule() throws Exception {
    CxxGenruleBuilder srcBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:src")).setOut("foo.cpp");
    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new DefaultBuildTargetSourcePath(srcBuilder.getTarget()))));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(srcBuilder.build(), libraryBuilder.build());
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ruleResolver.requireRule(
        libraryBuilder
            .getTarget()
            .withAppendedFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                CxxLibraryDescription.Type.STATIC.getFlavor()));
    verifySourcePaths(ruleResolver);
  }

  @Test
  public void headersFromCxxGenrule() throws Exception {
    CxxGenruleBuilder srcBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:src")).setOut("foo.h");
    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.cpp"))))
            .setHeaders(
                ImmutableSortedSet.of(new DefaultBuildTargetSourcePath(srcBuilder.getTarget())));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(srcBuilder.build(), libraryBuilder.build());
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ruleResolver.requireRule(
        libraryBuilder
            .getTarget()
            .withAppendedFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                CxxLibraryDescription.Type.STATIC.getFlavor()));
    verifySourcePaths(ruleResolver);
  }

  @Test
  public void locationMacroFromCxxGenrule() throws Exception {
    CxxGenruleBuilder srcBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:src")).setOut("linker.script");
    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.cpp"))))
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format("%s", LocationMacro.of(srcBuilder.getTarget()))));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(srcBuilder.build(), libraryBuilder.build());
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ruleResolver.requireRule(
        libraryBuilder
            .getTarget()
            .withAppendedFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                CxxLibraryDescription.Type.SHARED.getFlavor()));
    verifySourcePaths(ruleResolver);
  }

  @Test
  public void platformDeps() throws Exception {
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                depABuilder.build(), depBBuilder.build(), cxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    resolver.requireRule(depABuilder.getTarget());
    resolver.requireRule(depBBuilder.getTarget());
    CxxLibrary rule = (CxxLibrary) resolver.requireRule(cxxLibraryBuilder.getTarget());
    assertThat(
        rule.getNativeLinkableExportedDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.contains(resolver.requireRule(depABuilder.getTarget())));
    assertThat(
        rule.getCxxPreprocessorDeps(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.contains(resolver.requireRule(depABuilder.getTarget())));
  }

  @Test
  public void inferCaptureAllIncludesExportedDeps() throws Exception {
    CxxLibraryBuilder exportedDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:exported_dep"), cxxBuckConfig)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("dep.c"))));
    CxxLibraryBuilder ruleBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"), cxxBuckConfig)
            .setExportedDeps(ImmutableSortedSet.of(exportedDepBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(exportedDepBuilder.build(), ruleBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule rule =
        resolver.requireRule(
            ruleBuilder
                .getTarget()
                .withFlavors(
                    CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.get(),
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
  private void verifySourcePaths(BuildRuleResolver resolver) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    BuildContext buildContext = FakeBuildContext.withSourcePathResolver(pathResolver);
    BuildableContext buildableContext = new FakeBuildableContext();
    for (BuildRule rule : resolver.getBuildRules()) {
      rule.getBuildSteps(buildContext, buildableContext);
    }
  }
}
