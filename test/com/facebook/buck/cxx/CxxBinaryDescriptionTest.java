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
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.DependencyAggregationTestUtil;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@RunWith(Parameterized.class)
public class CxxBinaryDescriptionTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(
        new Object[] {"sandbox_sources=false"},
        new Object[] {"sandbox_sources=true"}
    );
  }

  private final CxxBuckConfig cxxBuckConfig;

  public CxxBinaryDescriptionTest(String sandboxConfig) {
    this.cxxBuckConfig = new CxxBuckConfig(
        FakeBuckConfig.builder().setSections("[cxx]", sandboxConfig).build());
  }

  private TargetGraph prepopulateWithSandbox(BuildTarget libTarget) {
    if (cxxBuckConfig.sandboxSources()) {
      return TargetGraphFactory.newInstance(mkSandboxNode(libTarget));
    } else {
      return TargetGraph.EMPTY;
    }
  }

  private TargetNode<CxxBinaryDescription.Arg, ?> mkSandboxNode(BuildTarget libTarget) {
    Optional<Map.Entry<Flavor, CxxLibraryDescription.Type>> type =
        CxxLibraryDescription.getLibType(libTarget);
    Set<Flavor> flavors = Sets.newHashSet(libTarget.getFlavors());
    if (type.isPresent()) {
      flavors.remove(type.get().getKey());
    }
    BuildTarget target = BuildTarget
        .builder(libTarget.getUnflavoredBuildTarget())
        .addAllFlavors(flavors)
        .addFlavors(CxxLibraryDescription.Type.SANDBOX_TREE.getFlavor())
        .build();
    return new CxxBinaryBuilder(target, cxxBuckConfig).build();
  }

  @Test
  public void createBuildRule() throws Exception {
    Assume.assumeFalse("this test is not for sandboxing", cxxBuckConfig.sandboxSources());

    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxBinaryBuilder.createDefaultPlatform();

    // Setup a genrule the generates a header we'll list.
    String genHeaderName = "test/foo.h";
    BuildTarget genHeaderTarget = BuildTargetFactory.newInstance("//:genHeader");
    GenruleBuilder genHeaderBuilder =
        GenruleBuilder
            .newGenruleBuilder(genHeaderTarget)
            .setOut(genHeaderName);

    // Setup a genrule the generates a source we'll list.
    String genSourceName = "test/foo.cpp";
    BuildTarget genSourceTarget = BuildTargetFactory.newInstance("//:genSource");
    GenruleBuilder genSourceBuilder =
        GenruleBuilder
            .newGenruleBuilder(genSourceTarget)
            .setOut(genSourceName);

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    CxxLibraryBuilder depBuilder = new CxxLibraryBuilder(depTarget)
        .setExportedHeaders(
            SourceList.ofUnnamedSources(
                ImmutableSortedSet.of(new FakeSourcePath("blah.h"))))
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.cpp"))));
    BuildTarget archiveTarget = BuildTarget.builder(depTarget)
        .addFlavors(CxxDescriptionEnhancer.STATIC_FLAVOR)
        .addFlavors(cxxPlatform.getFlavor())
        .build();
    BuildTarget headerSymlinkTreeTarget = BuildTarget.builder(depTarget)
        .addFlavors(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
        .addFlavors(cxxPlatform.getFlavor())
        .build();

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxBinaryBuilder cxxBinaryBuilder =
        new CxxBinaryBuilder(target, cxxBuckConfig)
              .setSrcs(
                  ImmutableSortedSet.of(
                      SourceWithFlags.of(new FakeSourcePath("test/bar.cpp")),
                      SourceWithFlags.of(new BuildTargetSourcePath(genSourceTarget))))
              .setHeaders(
                  ImmutableSortedSet.of(
                      new FakeSourcePath("test/bar.h"),
                      new BuildTargetSourcePath(genHeaderTarget)))
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
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    genHeaderBuilder.build(resolver, projectFilesystem, targetGraph);
    genSourceBuilder.build(resolver, projectFilesystem, targetGraph);
    depBuilder.build(resolver, projectFilesystem, targetGraph);
    CxxBinary binRule =
        (CxxBinary) cxxBinaryBuilder.build(resolver, projectFilesystem, targetGraph);

    assertThat(binRule.getLinkRule(), Matchers.instanceOf(CxxLink.class));
    CxxLink rule = (CxxLink) binRule.getLinkRule();
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactory.builder()
        .setParams(cxxBinaryBuilder.createBuildRuleParams(resolver, projectFilesystem))
        .setResolver(resolver)
        .setPathResolver(pathResolver)
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
        rule.getDeps().stream()
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()));

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule compileRule1 = resolver.getRule(
        cxxSourceRuleFactory.createCompileBuildTarget("test/bar.cpp"));
    assertNotNull(compileRule1);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(compileRule1)
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet()),
        Matchers.containsInAnyOrder(
            genHeaderTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE)));

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule compileRule2 =
        resolver.getRule(cxxSourceRuleFactory.createCompileBuildTarget(genSourceName));
    assertNotNull(compileRule2);
    assertThat(
        DependencyAggregationTestUtil.getDisaggregatedDeps(compileRule2)
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableList()),
        Matchers.containsInAnyOrder(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTreeTarget,
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE)));
  }

  @Test
  public void staticPicLinkStyle() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            prepopulateWithSandbox(target),
            new DefaultTargetNodeToBuildRuleTransformer());
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
    CxxBinaryBuilder leafCxxBinaryBuilder =
        new CxxBinaryBuilder(leafBinaryTarget, cxxBuckConfig);

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
    CxxBinary topLevelCxxBinary = (CxxBinary) topLevelCxxBinaryBuilder.build(resolver, filesystem);

    assertThat(
        BuildRules.getTransitiveRuntimeDeps(topLevelCxxBinary),
        Matchers.hasItem(leafCxxBinary));
  }

  @Test
  public void linkerFlagsLocationMacro() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            prepopulateWithSandbox(target),
            new DefaultTargetNodeToBuildRuleTransformer());
    Genrule dep =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target, cxxBuckConfig)
            .setLinkerFlags(ImmutableList.of("--linker-script=$(location //:dep)"));
    assertThat(
        builder.findImplicitDeps(),
        Matchers.hasItem(dep.getBuildTarget()));
    BuildRule binary = ((CxxBinary) builder.build(resolver)).getLinkRule();
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs()),
        Matchers.hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(
        binary.getDeps(),
        Matchers.hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithMatch() throws Exception {

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            prepopulateWithSandbox(target),
            new DefaultTargetNodeToBuildRuleTransformer());
    Genrule dep =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target, cxxBuckConfig)
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<String>>()
                    .add(
                        Pattern.compile(
                            Pattern.quote(
                                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().toString())),
                        ImmutableList.of("--linker-script=$(location //:dep)"))
                    .build());
    assertThat(
        builder.findImplicitDeps(),
        Matchers.hasItem(dep.getBuildTarget()));
    BuildRule binary = ((CxxBinary) builder.build(resolver)).getLinkRule();
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs()),
        Matchers.hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(
        binary.getDeps(),
        Matchers.hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithoutMatch() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            prepopulateWithSandbox(target),
            new DefaultTargetNodeToBuildRuleTransformer());
    Genrule dep =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target, cxxBuckConfig)
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<String>>()
                    .add(
                        Pattern.compile("nothing matches this string"),
                        ImmutableList.of("--linker-script=$(location //:dep)"))
                    .build());
    assertThat(
        builder.findImplicitDeps(),
        Matchers.hasItem(dep.getBuildTarget()));
    BuildRule binary = ((CxxBinary) builder.build(resolver)).getLinkRule();
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs()),
        Matchers.not(
            Matchers.hasItem(
                String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath()))));
    assertThat(
        binary.getDeps(),
        Matchers.not(Matchers.hasItem(dep)));
  }

  @Test
  public void binaryShouldLinkOwnRequiredLibraries() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxLibraryBuilder.createDefaultPlatform();

    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(
            BuildTargetFactory
                .newInstance("//:foo")
                .withFlavors(platform.getFlavor(), ImmutableFlavor.of("shared")),
            cxxBuckConfig);
    binaryBuilder
        .setLibraries(
            ImmutableSortedSet.of(
                FrameworkPath.ofSourcePath(new FakeSourcePath("/some/path/libs.dylib")),
                FrameworkPath.ofSourcePath(new FakeSourcePath("/another/path/liba.dylib"))))
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(new FakeSourcePath("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(binaryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    CxxBinary binary = (CxxBinary) binaryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(binary.getLinkRule(), Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary.getLinkRule()).getArgs()),
        Matchers.hasItems("-L", "/another/path", "/some/path", "-la", "-ls"));
  }

  @Test
  public void testBinaryWithStripFlavorHasStripLinkRuleWithCorrectStripStyle() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxLibraryBuilder.createDefaultPlatform();

    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(
            BuildTargetFactory
                .newInstance("//:foo")
                .withFlavors(
                    platform.getFlavor(),
                    ImmutableFlavor.of("shared"),
                    StripStyle.ALL_SYMBOLS.getFlavor()),
            cxxBuckConfig);
    binaryBuilder
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(new FakeSourcePath("foo.c"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(binaryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule resultRule = binaryBuilder.build(resolver, filesystem, targetGraph);
    assertThat(resultRule, Matchers.instanceOf(CxxBinary.class));
    assertThat(((CxxBinary) resultRule).getLinkRule(), Matchers.instanceOf(CxxStrip.class));

    CxxStrip strip = (CxxStrip) ((CxxBinary) resultRule).getLinkRule();
    assertThat(strip.getStripStyle(), equalTo(StripStyle.ALL_SYMBOLS));
  }
}
