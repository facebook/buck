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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
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
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.DependencyAggregationTestUtil;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.shell.ExportFile;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;

public class CxxLibraryDescriptionTest {

  private static Optional<SourcePath> getHeaderMaps(
      ProjectFilesystem filesystem,
      BuildTarget target,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    if (cxxPlatform.getCpp().resolve(resolver).supportsHeaderMaps() &&
        cxxPlatform.getCxxpp().resolve(resolver).supportsHeaderMaps()) {
      BuildTarget headerMapBuildTarget =
          CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
              target,
              cxxPlatform.getFlavor(),
              headerVisibility);
      return Optional.of(
          new BuildTargetSourcePath(
              headerMapBuildTarget,
              HeaderSymlinkTreeWithHeaderMap.getPath(filesystem, headerMapBuildTarget)));
    } else {
      return Optional.empty();
    }
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
  public void createBuildRule() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();

    // Setup a genrule the generates a header we'll list.
    String genHeaderName = "test/foo.h";
    BuildTarget genHeaderTarget = BuildTargetFactory.newInstance("//:genHeader");
    GenruleBuilder genHeaderBuilder = GenruleBuilder
        .newGenruleBuilder(genHeaderTarget)
        .setOut(genHeaderName);

    // Setup a genrule the generates a source we'll list.
    String genSourceName = "test/foo.cpp";
    BuildTarget genSourceTarget = BuildTargetFactory.newInstance("//:genSource");
    GenruleBuilder genSourceBuilder = GenruleBuilder
        .newGenruleBuilder(genSourceTarget)
        .setOut(genSourceName);

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    CxxLibraryBuilder depBuilder = new CxxLibraryBuilder(depTarget)
        .setExportedHeaders(
            SourceList.ofUnnamedSources(
                ImmutableSortedSet.of(new FakeSourcePath("blah.h"))))
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.cpp"))));
    BuildTarget headerSymlinkTreeTarget = BuildTarget.builder(depTarget)
        .addFlavors(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
        .addFlavors(cxxPlatform.getFlavor())
        .build();

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(
        filesystem.getRootPath(),
        target,
        cxxPlatform);
    String headerName = "test/bar.h";
    String privateHeaderName = "test/bar_private.h";
    CxxLibraryBuilder cxxLibraryBuilder = new CxxLibraryBuilder(target)
        .setExportedHeaders(
            ImmutableSortedSet.of(
                new FakeSourcePath(headerName),
                new BuildTargetSourcePath(genHeaderTarget)))
        .setHeaders(
            ImmutableSortedSet.of(new FakeSourcePath(privateHeaderName)))
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(new FakeSourcePath("test/bar.cpp")),
                SourceWithFlags.of(new BuildTargetSourcePath(genSourceTarget))))
        .setFrameworks(
            ImmutableSortedSet.of(
                FrameworkPath.ofSourcePath(new FakeSourcePath("/some/framework/path/s.dylib")),
                FrameworkPath.ofSourcePath(new FakeSourcePath("/another/framework/path/a.dylib"))))
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
        rule.getCxxPreprocessorInput(
            cxxPlatform,
            HeaderVisibility.PUBLIC);
    assertThat(
        publicInput.getFrameworks(),
        containsInAnyOrder(
            FrameworkPath.ofSourcePath(
                new PathSourcePath(filesystem, Paths.get("/some/framework/path/s.dylib"))),
            FrameworkPath.ofSourcePath(
                new PathSourcePath(filesystem, Paths.get("/another/framework/path/a.dylib")))));
    CxxSymlinkTreeHeaders publicHeaders = (CxxSymlinkTreeHeaders) publicInput.getIncludes().get(0);
    assertThat(
        publicHeaders.getIncludeType(),
        Matchers.equalTo(CxxPreprocessables.IncludeType.LOCAL));
    assertThat(
        publicHeaders.getNameToPathMap(),
        Matchers.equalTo(
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(headerName),
                new FakeSourcePath(headerName),
                Paths.get(genHeaderName),
                new BuildTargetSourcePath(genHeaderTarget))));
    assertThat(
        publicHeaders.getHeaderMap(),
        Matchers.equalTo(
            getHeaderMaps(
                filesystem,
                target,
                resolver,
                cxxPlatform,
                HeaderVisibility.PUBLIC)));

    // Verify private preprocessor input.
    CxxPreprocessorInput privateInput =
        rule.getCxxPreprocessorInput(
            cxxPlatform,
            HeaderVisibility.PRIVATE);
    assertThat(
        privateInput.getFrameworks(),
        containsInAnyOrder(
            FrameworkPath.ofSourcePath(
                new PathSourcePath(filesystem, Paths.get("/some/framework/path/s.dylib"))),
            FrameworkPath.ofSourcePath(
                new PathSourcePath(filesystem, Paths.get("/another/framework/path/a.dylib")))));
    CxxSymlinkTreeHeaders privateHeaders =
        (CxxSymlinkTreeHeaders) privateInput.getIncludes().get(0);
    assertThat(
        privateHeaders.getIncludeType(),
        Matchers.equalTo(CxxPreprocessables.IncludeType.LOCAL));
    assertThat(
        privateHeaders.getNameToPathMap(),
        Matchers.equalTo(
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(privateHeaderName),
                new FakeSourcePath(privateHeaderName))));
    assertThat(
        privateHeaders.getHeaderMap(),
        Matchers.equalTo(
            getHeaderMaps(
                filesystem,
                target,
                resolver,
                cxxPlatform,
                HeaderVisibility.PRIVATE)));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC);
    BuildRule archiveRule = resolver.getRule(
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            target,
            cxxPlatform.getFlavor(),
            CxxSourceRuleFactory.PicType.PDC));
    assertNotNull(archiveRule);
    assertEquals(
        ImmutableSet.of(
            cxxSourceRuleFactory.createCompileBuildTarget("test/bar.cpp"),
            cxxSourceRuleFactory.createCompileBuildTarget(genSourceName)),
        archiveRule.getDeps().stream()
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()));

    // Verify that the preprocess rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule preprocessRule1 = resolver.getRule(
        cxxSourceRuleFactory.createPreprocessBuildTarget("test/bar.cpp", CxxSource.Type.CXX));
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(preprocessRule1)
            .map(HasBuildTarget::getBuildTarget)
            ::iterator,
        containsInAnyOrder(
            genHeaderTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PUBLIC)));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule compileRule1 = resolver.getRule(
        cxxSourceRuleFactory.createCompileBuildTarget("test/bar.cpp"));
    assertNotNull(compileRule1);
    assertEquals(
        ImmutableSet.of(
            preprocessRule1.getBuildTarget()),
        compileRule1.getDeps().stream()
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()));

    // Verify that the preprocess rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule preprocessRule2 = resolver.getRule(
        cxxSourceRuleFactory.createPreprocessBuildTarget(genSourceName, CxxSource.Type.CXX));
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(preprocessRule2)
            .map(HasBuildTarget::getBuildTarget)
            ::iterator,
        containsInAnyOrder(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PUBLIC)));

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule compileRule2 =
        resolver.getRule(cxxSourceRuleFactory.createCompileBuildTarget(genSourceName));
    assertNotNull(compileRule2);
    assertEquals(
        ImmutableSet.of(
            preprocessRule2.getBuildTarget()),
        compileRule2.getDeps().stream()
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()));
  }

  @Test
  public void overrideSoname() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();

    String soname = "test_soname";

    // Generate the C++ library rules.
    BuildTarget target =
        BuildTargetFactory.newInstance(
            String.format("//:rule#shared,%s", CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor()));
    CxxLibraryBuilder ruleBuilder = new CxxLibraryBuilder(target)
        .setSoname(soname)
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.cpp"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(ruleBuilder.build());
    CxxLink rule = (CxxLink) ruleBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    Linker linker = cxxPlatform.getLd().resolve(resolver);
    ImmutableList<String> sonameArgs = ImmutableList.copyOf(linker.soname(soname));
    assertThat(
        Arg.stringify(rule.getArgs()),
        hasItems(sonameArgs.toArray(new String[sonameArgs.size()])));
  }

  @Test
  public void linkWhole() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();

    // Setup the target name and build params.
    BuildTarget target = BuildTargetFactory.newInstance("//:test");

    // First, create a cxx library without using link whole.
    CxxLibraryBuilder normalBuilder = new CxxLibraryBuilder(target);
    TargetGraph normalGraph = TargetGraphFactory.newInstance(normalBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(normalGraph, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary normal = (CxxLibrary) normalBuilder
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(new FakeSourcePath("test.cpp"))))
        .build(
            resolver,
            filesystem,
            normalGraph);

    // Lookup the link whole flags.
    Linker linker = cxxPlatform.getLd().resolve(resolver);
    ImmutableList<String> linkWholeFlags =
        FluentIterable.from(linker.linkWhole(new StringArg("sentinel")))
            .transformAndConcat(Arg::stringifyList)
            .filter(Predicates.not("sentinel"::equals))
            .toList();

    // Verify that the linker args contains the link whole flags.
    NativeLinkableInput input =
        normal.getNativeLinkableInput(
            cxxPlatform,
            Linker.LinkableDepType.STATIC);
    assertThat(
        Arg.stringify(input.getArgs()),
        Matchers.not(hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()]))));

    // Create a cxx library using link whole.
    CxxLibraryBuilder linkWholeBuilder =
        new CxxLibraryBuilder(target)
            .setLinkWhole(true)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.cpp"))));

    TargetGraph linkWholeGraph = TargetGraphFactory.newInstance(linkWholeBuilder.build());
    resolver = new BuildRuleResolver(normalGraph, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary linkWhole = (CxxLibrary) linkWholeBuilder
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(new FakeSourcePath("test.cpp"))))
        .build(
            resolver,
            filesystem,
            linkWholeGraph);

    // Verify that the linker args contains the link whole flags.
    NativeLinkableInput linkWholeInput =
        linkWhole.getNativeLinkableInput(
            cxxPlatform,
            Linker.LinkableDepType.STATIC);
    assertThat(
        Arg.stringify(linkWholeInput.getArgs()),
        hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()])));
  }

  @Test
  public void createCxxLibraryBuildRules() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();

    // Setup a normal C++ source
    String sourceName = "test/bar.cpp";

    // Setup a genrule the generates a header we'll list.
    String genHeaderName = "test/foo.h";
    BuildTarget genHeaderTarget = BuildTargetFactory.newInstance("//:genHeader");
    GenruleBuilder genHeaderBuilder = GenruleBuilder
        .newGenruleBuilder(genHeaderTarget)
        .setOut(genHeaderName);

    // Setup a genrule the generates a source we'll list.
    String genSourceName = "test/foo.cpp";
    BuildTarget genSourceTarget = BuildTargetFactory.newInstance("//:genSource");
    GenruleBuilder genSourceBuilder = GenruleBuilder
        .newGenruleBuilder(genSourceTarget)
        .setOut(genSourceName);

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    CxxLibraryBuilder depBuilder = new CxxLibraryBuilder(depTarget)
        .setExportedHeaders(
            SourceList.ofUnnamedSources(
                ImmutableSortedSet.of(new FakeSourcePath("blah.h"))))
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.cpp"))));
    BuildTarget sharedLibraryDepTarget = BuildTarget.builder(depTarget)
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .addFlavors(cxxPlatform.getFlavor())
        .build();
    BuildTarget headerSymlinkTreeTarget = BuildTarget.builder(depTarget)
        .addFlavors(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
        .addFlavors(cxxPlatform.getFlavor())
        .build();

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxSourceRuleFactory cxxSourceRuleFactoryPDC = CxxSourceRuleFactoryHelper.of(
        filesystem.getRootPath(),
        target,
        cxxPlatform,
        CxxSourceRuleFactory.PicType.PDC);
    CxxLibraryBuilder cxxLibraryBuilder = new CxxLibraryBuilder(target)
        .setExportedHeaders(
            ImmutableSortedMap.of(
                genHeaderName, new BuildTargetSourcePath(genHeaderTarget)))
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(new FakeSourcePath(sourceName)),
                SourceWithFlags.of(new BuildTargetSourcePath(genSourceTarget))))
        .setFrameworks(
            ImmutableSortedSet.of(
                FrameworkPath.ofSourcePath(new FakeSourcePath("/some/framework/path/s.dylib")),
                FrameworkPath.ofSourcePath(new FakeSourcePath("/another/framework/path/a.dylib"))))
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
        rule.getCxxPreprocessorInput(
            cxxPlatform,
            HeaderVisibility.PUBLIC);
    assertThat(
        publicInput.getFrameworks(),
        containsInAnyOrder(
            FrameworkPath.ofSourcePath(
                new PathSourcePath(filesystem, Paths.get("/some/framework/path/s.dylib"))),
            FrameworkPath.ofSourcePath(
                new PathSourcePath(filesystem, Paths.get("/another/framework/path/a.dylib")))));
    CxxSymlinkTreeHeaders publicHeaders = (CxxSymlinkTreeHeaders) publicInput.getIncludes().get(0);
    assertThat(
        publicHeaders.getIncludeType(),
        Matchers.equalTo(CxxPreprocessables.IncludeType.LOCAL));
    assertThat(
        publicHeaders.getNameToPathMap(),
        Matchers.equalTo(
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(genHeaderName),
                new BuildTargetSourcePath(genHeaderTarget))));
    assertThat(
        publicHeaders.getHeaderMap(),
        Matchers.equalTo(
            getHeaderMaps(
                filesystem,
                target,
                resolver,
                cxxPlatform,
                HeaderVisibility.PUBLIC)));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC);
    BuildRule staticRule = resolver.getRule(
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            target,
            cxxPlatform.getFlavor(),
            CxxSourceRuleFactory.PicType.PDC));
    assertNotNull(staticRule);
    assertEquals(
        ImmutableSet.of(
            cxxSourceRuleFactoryPDC.createCompileBuildTarget("test/bar.cpp"),
            cxxSourceRuleFactoryPDC.createCompileBuildTarget(genSourceName)),
        staticRule.getDeps().stream()
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule staticPreprocessRule1 = resolver.getRule(
        cxxSourceRuleFactoryPDC.createPreprocessBuildTarget("test/bar.cpp", CxxSource.Type.CXX));
    assertNotNull(staticPreprocessRule1);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(staticPreprocessRule1)
            .map(HasBuildTarget::getBuildTarget)
            ::iterator,
        containsInAnyOrder(
            genHeaderTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PUBLIC)));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule staticCompileRule1 = resolver.getRule(
        cxxSourceRuleFactoryPDC.createCompileBuildTarget("test/bar.cpp"));
    assertNotNull(staticCompileRule1);
    assertEquals(
        ImmutableSet.of(staticPreprocessRule1.getBuildTarget()),
        staticCompileRule1.getDeps().stream()
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()));

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule staticPreprocessRule2 = resolver.getRule(
        cxxSourceRuleFactoryPDC.createPreprocessBuildTarget(genSourceName, CxxSource.Type.CXX));
    assertNotNull(staticPreprocessRule2);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(staticPreprocessRule2)
            .map(HasBuildTarget::getBuildTarget)
            ::iterator,
        containsInAnyOrder(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PUBLIC)));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule staticCompileRule2 =
        resolver.getRule(cxxSourceRuleFactoryPDC.createCompileBuildTarget(genSourceName));
    assertNotNull(staticCompileRule2);
    assertEquals(
        ImmutableSet.of(staticPreprocessRule2.getBuildTarget()),
        staticCompileRule2.getDeps().stream()
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    CxxSourceRuleFactory cxxSourceRuleFactoryPIC = CxxSourceRuleFactoryHelper.of(
        filesystem.getRootPath(),
        target,
        cxxPlatform,
        CxxSourceRuleFactory.PicType.PIC);
    rule.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.SHARED);
    BuildRule sharedRule = resolver.getRule(
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            target,
            cxxPlatform.getFlavor(),
            Linker.LinkType.SHARED));
    assertNotNull(sharedRule);
    assertEquals(
        ImmutableSet.of(
            sharedLibraryDepTarget,
            cxxSourceRuleFactoryPIC.createCompileBuildTarget("test/bar.cpp"),
            cxxSourceRuleFactoryPIC.createCompileBuildTarget(genSourceName)),
        sharedRule.getDeps().stream()
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule sharedPreprocessRule1 = resolver.getRule(
        cxxSourceRuleFactoryPIC.createPreprocessBuildTarget("test/bar.cpp", CxxSource.Type.CXX));
    assertNotNull(sharedPreprocessRule1);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(sharedPreprocessRule1)
            .map(HasBuildTarget::getBuildTarget)
            ::iterator,
        containsInAnyOrder(
            genHeaderTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PUBLIC)));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule sharedCompileRule1 = resolver.getRule(
        cxxSourceRuleFactoryPIC.createCompileBuildTarget("test/bar.cpp"));
    assertNotNull(sharedCompileRule1);
    assertEquals(
        ImmutableSet.of(sharedPreprocessRule1.getBuildTarget()),
        sharedCompileRule1.getDeps().stream()
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()));

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule sharedPreprocessRule2 = resolver.getRule(
        cxxSourceRuleFactoryPIC.createPreprocessBuildTarget(genSourceName, CxxSource.Type.CXX));
    assertNotNull(sharedPreprocessRule2);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(sharedPreprocessRule2)
            .map(HasBuildTarget::getBuildTarget)
            ::iterator,
        containsInAnyOrder(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PUBLIC)));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule sharedCompileRule2 = resolver.getRule(
        cxxSourceRuleFactoryPIC.createCompileBuildTarget(genSourceName));
    assertNotNull(sharedCompileRule2);
    assertEquals(
        ImmutableSet.of(sharedPreprocessRule2.getBuildTarget()),
        sharedCompileRule2.getDeps().stream()
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()));
  }

  @Test
  public void supportedPlatforms() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");

    // First, make sure without any platform regex, we get something back for each of the interface
    // methods.
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(target)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.c"))));
    TargetGraph targetGraph1 = TargetGraphFactory.newInstance(cxxLibraryBuilder.build());
    BuildRuleResolver resolver1 =
        new BuildRuleResolver(targetGraph1, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary cxxLibrary = (CxxLibrary) cxxLibraryBuilder
        .build(resolver1, filesystem, targetGraph1);
    assertThat(
        cxxLibrary.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM).entrySet(),
        Matchers.not(empty()));
    assertThat(
        cxxLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Linker.LinkableDepType.SHARED)
            .getArgs(),
        Matchers.not(empty()));

    // Now, verify we get nothing when the supported platform regex excludes our platform.
    cxxLibraryBuilder.setSupportedPlatformsRegex(Pattern.compile("nothing"));
    TargetGraph targetGraph2 = TargetGraphFactory.newInstance(cxxLibraryBuilder.build());
    BuildRuleResolver resolver2 =
        new BuildRuleResolver(targetGraph2, new DefaultTargetNodeToBuildRuleTransformer());
    cxxLibrary = (CxxLibrary) cxxLibraryBuilder
        .build(resolver2, filesystem, targetGraph2);
    assertThat(
        cxxLibrary.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM).entrySet(),
        empty());
    assertThat(
        cxxLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Linker.LinkableDepType.SHARED)
            .getArgs(),
        empty());
  }

  @Test
  public void staticPicLibUsedForStaticPicLinkage() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary lib = (CxxLibrary) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CxxLibraryBuilder.createDefaultPlatform(),
            Linker.LinkableDepType.STATIC_PIC);
    Arg firstArg = nativeLinkableInput.getArgs().get(0);
    assertThat(firstArg, instanceOf(FileListableLinkerInputArg.class));
    ImmutableCollection<BuildRule> deps = firstArg.getDeps(new SourcePathResolver(resolver));
    assertThat(deps.size(), is(1));
    BuildRule buildRule = deps.asList().get(0);
    assertThat(
        buildRule.getBuildTarget().getFlavors(),
        hasItem(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR));
  }

  @Test
  public void linkerFlagsLocationMacro() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    Genrule dep =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxLibraryBuilder builder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule#shared,platform"))
            .setLinkerFlags(ImmutableList.of("--linker-script=$(location //:dep)"))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("foo.c"))));
    assertThat(
        builder.findImplicitDeps(),
        hasItem(dep.getBuildTarget()));
    BuildRule binary = builder.build(resolver);
    assertThat(binary, instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs()),
        hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(
        binary.getDeps(),
        hasItem(dep));
  }

  @Test
  public void locationMacroExpandedLinkerFlag() throws Exception {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory
        .newInstance("//foo:bar")
        .withFlavors(
            CxxDescriptionEnhancer.SHARED_FLAVOR,
            CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setLinkerFlags(ImmutableList.of("-Wl,--version-script=$(location //:loc)"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build(),
        locBuilder.build());
    ExportFile loc = (ExportFile) locBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    CxxLink lib = (CxxLink) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    assertThat(lib.getDeps(), hasItem(loc));
    assertThat(
        Arg.stringify(lib.getArgs()),
        hasItem(
            containsString(Preconditions.checkNotNull(loc.getPathToOutput()).toString())));
  }

  @Test
  public void locationMacroExpandedPlatformLinkerFlagPlatformMatch() throws Exception {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory
        .newInstance("//foo:bar")
        .withFlavors(
            CxxDescriptionEnhancer.SHARED_FLAVOR,
            CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setPlatformLinkerFlags(
        PatternMatchedCollection.<ImmutableList<String>>builder()
            .add(
                Pattern.compile(CxxLibraryBuilder.createDefaultPlatform().getFlavor().toString()),
                ImmutableList.of("-Wl,--version-script=$(location //:loc)"))
            .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build(),
        locBuilder.build());
    ExportFile loc = (ExportFile) locBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    CxxLink lib = (CxxLink) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    assertThat(lib.getDeps(), hasItem(loc));
    assertThat(
        Arg.stringify(lib.getArgs()),
        hasItem(
            String.format(
                "-Wl,--version-script=%s",
                Preconditions.checkNotNull(loc.getPathToOutput()).toAbsolutePath())));
    assertThat(
        Arg.stringify(lib.getArgs()),
        Matchers.not(hasItem(loc.getPathToOutput().toAbsolutePath().toString())));
  }

  @Test
  public void locationMacroExpandedPlatformLinkerFlagNoPlatformMatch() throws Exception {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory
        .newInstance("//foo:bar")
        .withFlavors(
            CxxDescriptionEnhancer.SHARED_FLAVOR,
            CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setPlatformLinkerFlags(
        PatternMatchedCollection.<ImmutableList<String>>builder()
            .add(
                Pattern.compile("notarealplatform"),
                ImmutableList.of("-Wl,--version-script=$(location //:loc)"))
            .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build(),
        locBuilder.build());
    ExportFile loc = (ExportFile) locBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    CxxLink lib = (CxxLink) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    assertThat(lib.getDeps(), Matchers.not(hasItem(loc)));
    assertThat(
        Arg.stringify(lib.getArgs()),
        Matchers.not(
            hasItem(
                containsString(
                    Preconditions.checkNotNull(loc.getPathToOutput()).toString()))));
  }

  @Test
  public void locationMacroExpandedExportedLinkerFlag() throws Exception {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setExportedLinkerFlags(ImmutableList.of("-Wl,--version-script=$(location //:loc)"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build(),
        locBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ExportFile loc = (ExportFile) locBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CxxLibraryBuilder.createDefaultPlatform(),
            Linker.LinkableDepType.SHARED);
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> arg.getDeps(pathResolver))
            .toSet(),
        hasItem(loc));
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs()),
        hasItem(
            containsString(
                Preconditions.checkNotNull(loc.getPathToOutput()).toString())));
  }

  @Test
  public void locationMacroExpandedExportedPlatformLinkerFlagPlatformMatch() throws Exception {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setExportedPlatformLinkerFlags(
        PatternMatchedCollection.<ImmutableList<String>>builder()
            .add(
                Pattern.compile(CxxLibraryBuilder.createDefaultPlatform().getFlavor().toString()),
                ImmutableList.of("-Wl,--version-script=$(location //:loc)"))
            .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build(),
        locBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ExportFile loc = (ExportFile) locBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CxxLibraryBuilder.createDefaultPlatform(),
            Linker.LinkableDepType.SHARED);
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> arg.getDeps(pathResolver))
            .toSet(),
        hasItem(loc));
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs()),
        hasItem(
            containsString(
                Preconditions.checkNotNull(loc.getPathToOutput()).toString())));
  }

  @Test
  public void locationMacroExpandedExportedPlatformLinkerFlagNoPlatformMatch() throws Exception {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory
        .newInstance("//foo:bar")
        .withFlavors(
            CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setExportedPlatformLinkerFlags(
        PatternMatchedCollection.<ImmutableList<String>>builder()
            .add(
                Pattern.compile("notarealplatform"),
                ImmutableList.of("-Wl,--version-script=$(location //:loc)"))
            .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build(),
        locBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ExportFile loc = (ExportFile) locBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            CxxLibraryBuilder.createDefaultPlatform(),
            Linker.LinkableDepType.SHARED);
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(arg -> arg.getDeps(pathResolver))
            .toSet(),
        Matchers.not(hasItem(loc)));
    assertThat(
        Arg.stringify(nativeLinkableInput.getArgs()),
        Matchers.not(
            hasItem(
                containsString(
                    Preconditions.checkNotNull(loc.getPathToOutput()).toString()))));
  }

  @Test
  public void libraryWithoutSourcesDoesntHaveOutput() throws Exception {
    BuildTarget target = BuildTargetFactory
        .newInstance("//foo:bar")
        .withFlavors(
            CxxDescriptionEnhancer.STATIC_FLAVOR,
            CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule lib = libBuilder.build(
        resolver,
        filesystem,
        targetGraph);

    assertThat(lib.getPathToOutput(), nullValue());
  }

  @Test
  public void libraryWithoutSourcesDoesntBuildAnything() throws Exception {
    BuildTarget target = BuildTargetFactory
        .newInstance("//foo:bar")
        .withFlavors(
            CxxDescriptionEnhancer.STATIC_FLAVOR,
            CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule lib = libBuilder.build(
        resolver,
        filesystem,
        targetGraph);

    assertThat(lib.getDeps(), is(empty()));
    assertThat(
        lib.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext()),
        is(empty()));
  }

  @Test
  public void nativeLinkableDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary dep =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    CxxLibrary rule =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build(resolver);
    assertThat(
        rule.getNativeLinkableDepsForPlatform(CxxLibraryBuilder.createDefaultPlatform()),
        Matchers.contains(dep));
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableExportedDepsForPlatform(
                CxxLibraryBuilder.createDefaultPlatform())),
        Matchers.<NativeLinkable>empty());
  }

  @Test
  public void nativeLinkableExportedDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary dep =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    CxxLibrary rule =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setExportedDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build(resolver);
    assertThat(
        ImmutableList.copyOf(rule.getNativeLinkableDepsForPlatform(
            CxxLibraryBuilder.createDefaultPlatform())),
        empty());
    assertThat(
        rule.getNativeLinkableExportedDepsForPlatform(CxxLibraryBuilder.createDefaultPlatform()),
        Matchers.contains(dep));
  }

  @Test
  public void nativeLinkTargetMode() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary rule =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setSoname("libsoname.so")
            .build(resolver);
    assertThat(
        rule.getNativeLinkTargetMode(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.equalTo(NativeLinkTargetMode.library("libsoname.so")));
  }

  @Test
  public void nativeLinkTargetDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary dep =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    CxxLibrary exportedDep =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:exported_dep"))
            .build(resolver);
    CxxLibrary rule =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setExportedDeps(
                ImmutableSortedSet.of(dep.getBuildTarget(), exportedDep.getBuildTarget()))
            .build(resolver);
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkTargetDeps(CxxLibraryBuilder.createDefaultPlatform())),
        hasItems(dep, exportedDep));
  }

  @Test
  public void nativeLinkTargetInput() throws Exception {
    CxxLibraryBuilder ruleBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setLinkerFlags(ImmutableList.of("--flag"))
            .setExportedLinkerFlags(ImmutableList.of("--exported-flag"));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(ruleBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary rule = (CxxLibrary) ruleBuilder.build(resolver);
    NativeLinkableInput input =
        rule.getNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        Arg.stringify(input.getArgs()),
        hasItems("--flag", "--exported-flag"));
  }

  @Test
  public void exportedDepsArePropagatedToRuntimeDeps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxBinaryBuilder cxxBinaryBuilder =
        new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"));
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setExportedDeps(ImmutableSortedSet.of(cxxBinaryBuilder.getTarget()));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(cxxLibraryBuilder.build(), cxxBinaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    cxxBinaryBuilder.build(resolver, filesystem);
    CxxLibrary cxxLibrary = (CxxLibrary) cxxLibraryBuilder.build(resolver, filesystem);
    assertThat(
        cxxLibrary.getRuntimeDeps().stream()
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()),
        hasItem(cxxBinaryBuilder.getTarget()));
  }

  @Test
  public void sharedLibraryShouldLinkOwnRequiredLibraries() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxLibraryBuilder.createDefaultPlatform();

    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(
            BuildTargetFactory
                .newInstance("//:foo")
                .withFlavors(platform.getFlavor(), ImmutableFlavor.of("shared")));
    libraryBuilder
        .setLibraries(
            ImmutableSortedSet.of(
                FrameworkPath.ofSourceTreePath(
                    new SourceTreePath(
                        PBXReference.SourceTree.SDKROOT,
                        Paths.get("/usr/lib/libz.dylib"),
                        Optional.empty())),
                FrameworkPath.ofSourcePath(new FakeSourcePath("/another/path/liba.dylib"))))
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(new FakeSourcePath("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLink library = (CxxLink) libraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        Arg.stringify(library.getArgs()),
        hasItems("-L", "/another/path", "$SDKROOT/usr/lib", "-la", "-lz"));
  }

  @Test
  public void sharedLibraryShouldLinkOwnRequiredLibrariesForCxxLibrary() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxLibraryBuilder.createDefaultPlatform();

    ImmutableSortedSet<FrameworkPath> libraries = ImmutableSortedSet.of(
        FrameworkPath.ofSourcePath(new FakeSourcePath("/some/path/libs.dylib")),
        FrameworkPath.ofSourcePath(new FakeSourcePath("/another/path/liba.dylib")));

    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:foo#shared"));
    libraryBuilder
        .setLibraries(libraries)
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(new FakeSourcePath("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary library = (CxxLibrary) libraryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(
        library.getNativeLinkTargetInput(platform).getLibraries(),
        Matchers.equalTo(libraries));
  }

  @Test
  public void ruleWithoutHeadersDoesNotUseSymlinkTree() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(cxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder.build(resolver, filesystem);
    CxxPreprocessorInput input =
        rule.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, HeaderVisibility.PUBLIC);
    assertThat(
        getHeaderNames(input.getIncludes()),
        empty());
    assertThat(
        input.getSystemIncludeRoots(),
        empty());
    assertThat(
        ImmutableSortedSet.copyOf(input.getDeps(resolver, pathResolver)),
        empty());
  }

  @Test
  public void thinArchiveSettingIsPropagatedToArchive() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target =
        BuildTargetFactory.newInstance("//:rule")
            .withFlavors(
                CxxDescriptionEnhancer.STATIC_FLAVOR,
                CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    CxxLibraryBuilder libBuilder =
        new CxxLibraryBuilder(
            target,
            new CxxBuckConfig(
                FakeBuckConfig.builder().setSections("[cxx]", "archive_contents=thin").build()),
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    Archive lib = (Archive) libBuilder.build(resolver, filesystem);
    assertThat(lib.getContents(), Matchers.equalTo(Archive.Contents.THIN));
  }

  @Test
  public void forceStatic() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setPreferredLinkage(NativeLinkable.Linkage.STATIC);
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(cxxLibraryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder.build(resolver, filesystem);
    assertThat(
        rule.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.equalTo(NativeLinkable.Linkage.STATIC));
  }

}
