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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.DependencyAggregationTestUtil;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionUniverse;
import com.facebook.buck.versions.VersionUniverseVersionSelector;
import com.facebook.buck.versions.VersionedAliasBuilder;
import com.facebook.buck.versions.VersionedTargetGraphBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CxxBinaryDescriptionTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(
        new Object[] {"sandbox_sources=false"}, new Object[] {"sandbox_sources=true"});
  }

  private final CxxBuckConfig cxxBuckConfig;

  public CxxBinaryDescriptionTest(String sandboxConfig) {
    this.cxxBuckConfig =
        new CxxBuckConfig(FakeBuckConfig.builder().setSections("[cxx]", sandboxConfig).build());
  }

  private TargetGraph prepopulateWithSandbox(BuildTarget libTarget) {
    if (cxxBuckConfig.sandboxSources()) {
      return TargetGraphFactory.newInstance(mkSandboxNode(libTarget));
    } else {
      return TargetGraph.EMPTY;
    }
  }

  private TargetNode<CxxBinaryDescriptionArg, ?> mkSandboxNode(BuildTarget libTarget) {
    Optional<Map.Entry<Flavor, CxxLibraryDescription.Type>> type =
        CxxLibraryDescription.getLibType(libTarget);
    Set<Flavor> flavors = Sets.newHashSet(libTarget.getFlavors());
    if (type.isPresent()) {
      flavors.remove(type.get().getKey());
    }
    BuildTarget target =
        BuildTarget.builder(libTarget.getUnflavoredBuildTarget())
            .addAllFlavors(flavors)
            .addFlavors(CxxLibraryDescription.Type.SANDBOX_TREE.getFlavor())
            .build();
    return new CxxBinaryBuilder(target, cxxBuckConfig).build();
  }

  @Test
  public void createBuildRule() throws Exception {
    Assume.assumeFalse("this test is not for sandboxing", cxxBuckConfig.sandboxSources());

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
                SourceList.ofUnnamedSources(ImmutableSortedSet.of(new FakeSourcePath("blah.h"))))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.cpp"))));
    BuildTarget archiveTarget =
        BuildTarget.builder(depTarget)
            .addFlavors(CxxDescriptionEnhancer.STATIC_FLAVOR)
            .addFlavors(cxxPlatform.getFlavor())
            .build();
    BuildTarget headerSymlinkTreeTarget =
        BuildTarget.builder(depTarget)
            .addFlavors(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
            .addFlavors(CxxPreprocessables.HeaderMode.SYMLINK_TREE_ONLY.getFlavor())
            .build();

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxBinaryBuilder cxxBinaryBuilder =
        new CxxBinaryBuilder(target, cxxBuckConfig)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("test/bar.cpp")),
                    SourceWithFlags.of(new DefaultBuildTargetSourcePath(genSourceTarget))))
            .setHeaders(
                ImmutableSortedSet.of(
                    new FakeSourcePath("test/bar.h"),
                    new DefaultBuildTargetSourcePath(genHeaderTarget)))
            .setDeps(ImmutableSortedSet.of(depTarget));

    // Create the target graph.
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            genHeaderBuilder.build(),
            genSourceBuilder.build(),
            depBuilder.build(),
            cxxBinaryBuilder.build());

    // Create the build rules.
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    genHeaderBuilder.build(resolver, projectFilesystem, targetGraph);
    genSourceBuilder.build(resolver, projectFilesystem, targetGraph);
    depBuilder.build(resolver, projectFilesystem, targetGraph);
    CxxBinary binRule = cxxBinaryBuilder.build(resolver, projectFilesystem, targetGraph);

    assertThat(binRule.getLinkRule(), Matchers.instanceOf(CxxLink.class));
    CxxLink rule = (CxxLink) binRule.getLinkRule();
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactory.builder()
            .setParams(cxxBinaryBuilder.createBuildRuleParams(resolver, projectFilesystem))
            .setResolver(resolver)
            .setPathResolver(pathResolver)
            .setRuleFinder(ruleFinder)
            .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
            .setCxxPlatform(cxxPlatform)
            .setPicType(CxxSourceRuleFactory.PicType.PDC)
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
        Matchers.containsInAnyOrder(
            genHeaderTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor())));

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule compileRule2 =
        resolver.getRule(cxxSourceRuleFactory.createCompileBuildTarget(genSourceName));
    assertNotNull(compileRule2);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(compileRule2)
            .map(BuildRule::getBuildTarget)
            .collect(MoreCollectors.toImmutableList()),
        Matchers.containsInAnyOrder(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor())));
  }

  @Test
  public void staticPicLinkStyle() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            prepopulateWithSandbox(target), new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    new CxxBinaryBuilder(target, cxxBuckConfig)
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))))
        .build(resolver, filesystem);
  }

  @Test
  public void runtimeDepOnDeps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget leafBinaryTarget = BuildTargetFactory.newInstance("//:dep");
    CxxBinaryBuilder leafCxxBinaryBuilder = new CxxBinaryBuilder(leafBinaryTarget, cxxBuckConfig);

    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:lib");
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(libraryTarget).setDeps(ImmutableSortedSet.of(leafBinaryTarget));

    BuildTarget topLevelBinaryTarget = BuildTargetFactory.newInstance("//:bin");
    CxxBinaryBuilder topLevelCxxBinaryBuilder =
        new CxxBinaryBuilder(topLevelBinaryTarget, cxxBuckConfig)
            .setDeps(ImmutableSortedSet.of(libraryTarget));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                leafCxxBinaryBuilder.build(),
                cxxLibraryBuilder.build(),
                topLevelCxxBinaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule leafCxxBinary = leafCxxBinaryBuilder.build(resolver, filesystem);
    cxxLibraryBuilder.build(resolver, filesystem);
    CxxBinary topLevelCxxBinary = topLevelCxxBinaryBuilder.build(resolver, filesystem);

    assertThat(
        BuildRules.getTransitiveRuntimeDeps(topLevelCxxBinary, resolver),
        Matchers.hasItem(leafCxxBinary.getBuildTarget()));
  }

  @Test
  public void linkerFlagsLocationMacro() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            prepopulateWithSandbox(target), new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target, cxxBuckConfig)
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))));
    assertThat(builder.build().getExtraDeps(), Matchers.hasItem(dep.getBuildTarget()));
    BuildRule binary = builder.build(resolver).getLinkRule();
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs(), pathResolver),
        Matchers.hasItem(
            String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath(pathResolver))));
    assertThat(binary.getBuildDeps(), Matchers.hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithMatch() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            prepopulateWithSandbox(target), new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target, cxxBuckConfig)
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<StringWithMacros>>()
                    .add(
                        Pattern.compile(
                            Pattern.quote(
                                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().toString())),
                        ImmutableList.of(
                            StringWithMacrosUtils.format(
                                "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))))
                    .build());
    assertThat(builder.build().getExtraDeps(), Matchers.hasItem(dep.getBuildTarget()));
    BuildRule binary = builder.build(resolver).getLinkRule();
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs(), pathResolver),
        Matchers.hasItem(
            String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath(pathResolver))));
    assertThat(binary.getBuildDeps(), Matchers.hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithoutMatch() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            prepopulateWithSandbox(target), new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target, cxxBuckConfig)
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<StringWithMacros>>()
                    .add(
                        Pattern.compile("nothing matches this string"),
                        ImmutableList.of(
                            StringWithMacrosUtils.format(
                                "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))))
                    .build());
    assertThat(builder.build().getExtraDeps(), Matchers.hasItem(dep.getBuildTarget()));
    BuildRule binary = builder.build(resolver).getLinkRule();
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs(), pathResolver),
        Matchers.not(
            Matchers.hasItem(
                String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath(pathResolver)))));
    assertThat(binary.getBuildDeps(), Matchers.not(Matchers.hasItem(dep)));
  }

  @Test
  public void binaryShouldLinkOwnRequiredLibraries() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxPlatformUtils.DEFAULT_PLATFORM;

    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(
            BuildTargetFactory.newInstance("//:foo")
                .withFlavors(platform.getFlavor(), InternalFlavor.of("shared")),
            cxxBuckConfig);
    binaryBuilder
        .setLibraries(
            ImmutableSortedSet.of(
                FrameworkPath.ofSourcePath(new FakeSourcePath("/some/path/libs.dylib")),
                FrameworkPath.ofSourcePath(new FakeSourcePath("/another/path/liba.dylib"))))
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(binaryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    CxxBinary binary = binaryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(binary.getLinkRule(), Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary.getLinkRule()).getArgs(), pathResolver),
        Matchers.hasItems("-L", "/another/path", "/some/path", "-la", "-ls"));
  }

  @Test
  public void testBinaryWithStripFlavorHasStripLinkRuleWithCorrectStripStyle() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxPlatformUtils.DEFAULT_PLATFORM;

    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(
            BuildTargetFactory.newInstance("//:foo")
                .withFlavors(
                    platform.getFlavor(),
                    InternalFlavor.of("shared"),
                    StripStyle.ALL_SYMBOLS.getFlavor()),
            cxxBuckConfig);
    binaryBuilder.setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(binaryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule resultRule = binaryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(resultRule, Matchers.instanceOf(CxxBinary.class));
    assertThat(((CxxBinary) resultRule).getLinkRule(), Matchers.instanceOf(CxxStrip.class));

    CxxStrip strip = (CxxStrip) ((CxxBinary) resultRule).getLinkRule();
    assertThat(strip.getStripStyle(), equalTo(StripStyle.ALL_SYMBOLS));
  }

  @Test
  public void depQuery() throws Exception {
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary transitiveDep = (CxxLibrary) transitiveDepBuilder.build(resolver, targetGraph);
    depBuilder.build(resolver, targetGraph);
    CxxBinary binary = builder.build(resolver, targetGraph);
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
        VersionedTargetGraphBuilder.transform(
                new VersionUniverseVersionSelector(
                    unversionedTargetGraph, ImmutableMap.of("1", universe1, "2", universe2)),
                TargetGraphAndBuildTargets.of(
                    unversionedTargetGraph, ImmutableSet.of(builder.getTarget())),
                new ForkJoinPool(),
                new DefaultTypeCoercerFactory())
            .getTargetGraph();

    assertThat(
        versionedTargetGraph.get(builder.getTarget()).getSelectedVersions(),
        equalTo(Optional.of(universe1.getVersions())));
  }

  @Test
  public void testDefaultPlatformArg() throws Exception {
    CxxPlatform alternatePlatform =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("alternate"));
    FlavorDomain<CxxPlatform> cxxPlatforms =
        new FlavorDomain<>(
            "C/C++ Platform",
            ImmutableMap.of(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    CxxBinary binary = binaryBuilder.build(resolver, targetGraph);
    assertThat(binary.getCxxPlatform(), equalTo(alternatePlatform));
  }
}
