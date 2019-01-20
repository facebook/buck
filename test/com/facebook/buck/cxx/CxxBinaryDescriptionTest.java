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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.ImmutableBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.rules.impl.DependencyAggregationTestUtil;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.versions.ParallelVersionedTargetGraphBuilder;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionUniverse;
import com.facebook.buck.versions.VersionUniverseVersionSelector;
import com.facebook.buck.versions.VersionedAliasBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxBinaryDescriptionTest {

  //  private TargetGraph prepopulateWithSandbox(BuildTarget libTarget) {
  //      return TargetGraph.EMPTY;
  //  }

  private TargetNode<CxxBinaryDescriptionArg> mkSandboxNode(BuildTarget libTarget) {
    Optional<Map.Entry<Flavor, CxxLibraryDescription.Type>> type =
        CxxLibraryDescription.getLibType(libTarget);
    Set<Flavor> flavors = Sets.newHashSet(libTarget.getFlavors());
    if (type.isPresent()) {
      flavors.remove(type.get().getKey());
    }
    BuildTarget target = ImmutableBuildTarget.of(libTarget.getUnflavoredBuildTarget(), flavors);
    return new CxxBinaryBuilder(target).build();
  }

  @Test
  public void createBuildRule() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
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
        new CxxLibraryBuilder(depTarget)
            .setExportedHeaders(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("blah.h"))))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test.cpp"))));
    BuildTarget archiveTarget =
        depTarget.withAppendedFlavors(
            CxxDescriptionEnhancer.STATIC_FLAVOR, cxxPlatform.getFlavor());
    BuildTarget headerSymlinkTreeTarget =
        depTarget.withAppendedFlavors(
            CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
            HeaderMode.SYMLINK_TREE_ONLY.getFlavor());

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxBinaryBuilder cxxBinaryBuilder =
        new CxxBinaryBuilder(target)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("test/bar.cpp")),
                    SourceWithFlags.of(DefaultBuildTargetSourcePath.of(genSourceTarget))))
            .setHeaders(
                ImmutableSortedSet.of(
                    FakeSourcePath.of("test/bar.h"),
                    DefaultBuildTargetSourcePath.of(genHeaderTarget)))
            .setDeps(ImmutableSortedSet.of(depTarget));

    // Create the target graph.
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            genHeaderBuilder.build(),
            genSourceBuilder.build(),
            depBuilder.build(),
            cxxBinaryBuilder.build());

    // Create the build rules.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    genHeaderBuilder.build(graphBuilder, projectFilesystem, targetGraph);
    genSourceBuilder.build(graphBuilder, projectFilesystem, targetGraph);
    depBuilder.build(graphBuilder, projectFilesystem, targetGraph);
    CxxBinary binRule = cxxBinaryBuilder.build(graphBuilder, projectFilesystem, targetGraph);

    assertThat(binRule.getLinkRule(), Matchers.instanceOf(CxxLink.class));
    CxxLink rule = (CxxLink) binRule.getLinkRule();
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactory.builder()
            .setBaseBuildTarget(target)
            .setProjectFilesystem(projectFilesystem)
            .setActionGraphBuilder(graphBuilder)
            .setPathResolver(pathResolver)
            .setRuleFinder(ruleFinder)
            .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
            .setCxxPlatform(cxxPlatform)
            .setPicType(PicType.PDC)
            .build();

    // Check that link rule has the expected deps: the object files for our sources and the
    // archive from the dependency.
    assertEquals(
        ImmutableSet.of(
            cxxSourceRuleFactory.createCompileBuildTarget("test/bar.cpp"),
            cxxSourceRuleFactory.createCompileBuildTarget(genSourceName),
            archiveTarget),
        rule.getBuildDeps()
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
        Matchers.containsInAnyOrder(
            genHeaderTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor())));

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule compileRule2 =
        graphBuilder.getRule(cxxSourceRuleFactory.createCompileBuildTarget(genSourceName));
    assertNotNull(compileRule2);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(compileRule2)
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableList.toImmutableList()),
        Matchers.containsInAnyOrder(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor())));
  }

  @Test
  public void staticPicLinkStyle() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(TargetGraph.EMPTY);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    new CxxBinaryBuilder(target)
        .setSrcs(
            ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of(filesystem, "test.cpp"))))
        .build(graphBuilder, filesystem);
  }

  @Test
  public void runtimeDepOnDeps() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget leafBinaryTarget = BuildTargetFactory.newInstance("//:dep");
    CxxBinaryBuilder leafCxxBinaryBuilder = new CxxBinaryBuilder(leafBinaryTarget);

    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:lib");
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(libraryTarget).setDeps(ImmutableSortedSet.of(leafBinaryTarget));

    BuildTarget topLevelBinaryTarget = BuildTargetFactory.newInstance("//:bin");
    CxxBinaryBuilder topLevelCxxBinaryBuilder =
        new CxxBinaryBuilder(topLevelBinaryTarget).setDeps(ImmutableSortedSet.of(libraryTarget));

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                leafCxxBinaryBuilder.build(),
                cxxLibraryBuilder.build(),
                topLevelCxxBinaryBuilder.build()));
    BuildRule leafCxxBinary = leafCxxBinaryBuilder.build(graphBuilder, filesystem);
    cxxLibraryBuilder.build(graphBuilder, filesystem);
    CxxBinary topLevelCxxBinary = topLevelCxxBinaryBuilder.build(graphBuilder, filesystem);

    assertThat(
        BuildRules.getTransitiveRuntimeDeps(topLevelCxxBinary, graphBuilder),
        Matchers.hasItem(leafCxxBinary.getBuildTarget()));
  }

  @Test
  public void linkerFlagsLocationMacro() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target)
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))));
    assertThat(builder.build().getExtraDeps(), Matchers.hasItem(dep.getBuildTarget()));
    BuildRule binary = builder.build(graphBuilder).getLinkRule();
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs(), pathResolver),
        Matchers.hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(binary.getBuildDeps(), Matchers.hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithMatch() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target)
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<StringWithMacros>>()
                    .add(
                        Pattern.compile(
                            Pattern.quote(CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR.toString())),
                        ImmutableList.of(
                            StringWithMacrosUtils.format(
                                "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))))
                    .build());
    assertThat(builder.build().getExtraDeps(), Matchers.hasItem(dep.getBuildTarget()));
    BuildRule binary = builder.build(graphBuilder).getLinkRule();
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs(), pathResolver),
        Matchers.hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(binary.getBuildDeps(), Matchers.hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithoutMatch() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target)
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<StringWithMacros>>()
                    .add(
                        Pattern.compile("nothing matches this string"),
                        ImmutableList.of(
                            StringWithMacrosUtils.format(
                                "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))))
                    .build());
    assertThat(builder.build().getExtraDeps(), Matchers.hasItem(dep.getBuildTarget()));
    BuildRule binary = builder.build(graphBuilder).getLinkRule();
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs(), pathResolver),
        Matchers.not(
            Matchers.hasItem(
                String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath()))));
    assertThat(binary.getBuildDeps(), Matchers.not(Matchers.hasItem(dep)));
  }

  @Test
  public void binaryShouldLinkOwnRequiredLibraries() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxPlatformUtils.DEFAULT_PLATFORM;

    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(
            BuildTargetFactory.newInstance("//:foo")
                .withFlavors(platform.getFlavor(), InternalFlavor.of("shared")));
    binaryBuilder
        .setLibraries(
            ImmutableSortedSet.of(
                FrameworkPath.ofSourcePath(FakeSourcePath.of("/some/path/libs.dylib")),
                FrameworkPath.ofSourcePath(FakeSourcePath.of("/another/path/liba.dylib"))))
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(binaryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    CxxBinary binary = binaryBuilder.build(graphBuilder, filesystem, targetGraph);
    assertThat(binary.getLinkRule(), Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary.getLinkRule()).getArgs(), pathResolver),
        Matchers.hasItems("-L", "/another/path", "/some/path", "-la", "-ls"));
  }

  @Test
  public void testBinaryWithStripFlavorHasStripLinkRuleWithCorrectStripStyle() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxPlatformUtils.DEFAULT_PLATFORM;

    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(
            BuildTargetFactory.newInstance("//:foo")
                .withFlavors(
                    platform.getFlavor(),
                    InternalFlavor.of("shared"),
                    StripStyle.ALL_SYMBOLS.getFlavor()));
    binaryBuilder.setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(binaryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    CxxBinary resultRule = binaryBuilder.build(graphBuilder, filesystem, targetGraph);
    assertThat(resultRule, Matchers.instanceOf(CxxBinary.class));
    assertThat(resultRule.getLinkRule(), Matchers.instanceOf(CxxStrip.class));

    CxxStrip strip = (CxxStrip) resultRule.getLinkRule();
    assertThat(strip.getStripStyle(), equalTo(StripStyle.ALL_SYMBOLS));
  }

  @Test
  public void depQuery() {
    CxxLibraryBuilder transitiveDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"));
    CxxLibraryBuilder depBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setDeps(ImmutableSortedSet.of(transitiveDepBuilder.getTarget()));
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setDepQuery(Query.of("filter(transitive, deps(//:dep))"));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            transitiveDepBuilder.build(), depBuilder.build(), builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    CxxLibrary transitiveDep = (CxxLibrary) transitiveDepBuilder.build(graphBuilder, targetGraph);
    depBuilder.build(graphBuilder, targetGraph);
    CxxBinary binary = builder.build(graphBuilder, targetGraph);
    // TODO(agallagher): should also test that `:dep` does *not* get included.
    assertThat(binary.getBuildDeps(), Matchers.hasItem(transitiveDep));
  }

  @Test
  public void versionUniverse() throws Exception {
    GenruleBuilder transitiveDepBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:tdep")).setOut("out");
    VersionedAliasBuilder depBuilder =
        new VersionedAliasBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setVersions(
                ImmutableMap.of(
                    Version.of("1.0"), transitiveDepBuilder.getTarget(),
                    Version.of("2.0"), transitiveDepBuilder.getTarget()));
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setVersionUniverse("1")
            .setDeps(ImmutableSortedSet.of(depBuilder.getTarget()));

    TargetGraph unversionedTargetGraph =
        TargetGraphFactory.newInstance(
            transitiveDepBuilder.build(), depBuilder.build(), builder.build());

    VersionUniverse universe1 =
        VersionUniverse.of(ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")));
    VersionUniverse universe2 =
        VersionUniverse.of(ImmutableMap.of(depBuilder.getTarget(), Version.of("2.0")));

    TargetGraph versionedTargetGraph =
        ParallelVersionedTargetGraphBuilder.transform(
                new VersionUniverseVersionSelector(
                    unversionedTargetGraph, ImmutableMap.of("1", universe1, "2", universe2)),
                TargetGraphAndBuildTargets.of(
                    unversionedTargetGraph, ImmutableSet.of(builder.getTarget())),
                new ForkJoinPool(),
                new DefaultTypeCoercerFactory(),
                20)
            .getTargetGraph();

    assertThat(
        versionedTargetGraph.get(builder.getTarget()).getSelectedVersions(),
        equalTo(Optional.of(universe1.getVersions())));
  }

  @Test
  public void testDefaultPlatformArg() {
    CxxPlatform alternatePlatform =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("alternate"));
    FlavorDomain<CxxPlatform> cxxPlatforms =
        new FlavorDomain<>(
            "C/C++ Platform",
            ImmutableMap.of(
                CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                alternatePlatform.getFlavor(),
                alternatePlatform));
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(
            BuildTargetFactory.newInstance("//:foo"),
            CxxPlatformUtils.DEFAULT_PLATFORM,
            cxxPlatforms);
    binaryBuilder.setDefaultPlatform(alternatePlatform.getFlavor());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(binaryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    CxxBinary binary = binaryBuilder.build(graphBuilder, targetGraph);
    assertThat(binary.getCxxPlatform(), equalTo(alternatePlatform));
  }

  @Test
  public void testDefaultExecutableNameArg() {
    CxxPlatform alternatePlatform =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("alternate"));
    FlavorDomain<CxxPlatform> cxxPlatforms =
        new FlavorDomain<>(
            "C/C++ Platform",
            ImmutableMap.of(
                CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                alternatePlatform.getFlavor(),
                alternatePlatform));
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(
            BuildTargetFactory.newInstance("//:foo"),
            CxxPlatformUtils.DEFAULT_PLATFORM,
            cxxPlatforms);
    binaryBuilder.setDefaultPlatform(alternatePlatform.getFlavor());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(binaryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    CxxBinary binary = binaryBuilder.build(graphBuilder, targetGraph);
    assertEquals(
        binary.getLinkRule().getSourcePathToOutput().toString(),
        "Pair(//:foo#binary, buck-out" + File.separator + "gen" + File.separator + "foo)");
  }

  @Test
  public void testUserSpecifiedExecutableNameArg() {
    CxxPlatform alternatePlatform =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("alternate"));
    FlavorDomain<CxxPlatform> cxxPlatforms =
        new FlavorDomain<>(
            "C/C++ Platform",
            ImmutableMap.of(
                CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                alternatePlatform.getFlavor(),
                alternatePlatform));
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(
            BuildTargetFactory.newInstance("//:foo"),
            CxxPlatformUtils.DEFAULT_PLATFORM,
            cxxPlatforms);
    binaryBuilder.setExecutableName(Optional.of("FooBarBaz"));
    binaryBuilder.setDefaultPlatform(alternatePlatform.getFlavor());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(binaryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    CxxBinary binary = binaryBuilder.build(graphBuilder, targetGraph);
    assertEquals(
        binary.getLinkRule().getSourcePathToOutput().toString(),
        "Pair(//:foo#binary, buck-out"
            + File.separator
            + "gen"
            + File.separator
            + "foo"
            + File.separator
            + "FooBarBaz)");
  }
}
