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

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class HaskellLibraryDescriptionTest {

  @Test
  public void compilerFlags() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    String flag = "-compiler-flag";
    HaskellLibraryBuilder builder =
        new HaskellLibraryBuilder(target).setCompilerFlags(ImmutableList.of(flag));
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(builder.build()));
    HaskellLibrary library = builder.build(graphBuilder);
    library.getCompileInput(
        HaskellTestUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, false);
    BuildTarget compileTarget =
        HaskellDescriptionUtils.getCompileBuildTarget(
            target, HaskellTestUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, false);
    HaskellCompileRule rule = graphBuilder.getRuleWithType(compileTarget, HaskellCompileRule.class);
    assertThat(rule.getFlags(), Matchers.hasItem(flag));
  }

  @Test
  public void targetsAndOutputsAreDifferentBetweenLinkStyles() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(TargetGraphFactory.newInstance());
    BuildTarget baseTarget = BuildTargetFactory.newInstance("//:rule");

    BuildRule staticLib =
        new HaskellLibraryBuilder(
                baseTarget.withFlavors(
                    CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR,
                    HaskellLibraryDescription.Type.STATIC.getFlavor()))
            .build(graphBuilder);
    BuildRule staticPicLib =
        new HaskellLibraryBuilder(
                baseTarget.withFlavors(
                    CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR,
                    HaskellLibraryDescription.Type.STATIC_PIC.getFlavor()))
            .build(graphBuilder);
    BuildRule sharedLib =
        new HaskellLibraryBuilder(
                baseTarget.withFlavors(
                    CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR,
                    HaskellLibraryDescription.Type.SHARED.getFlavor()))
            .build(graphBuilder);

    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    ImmutableList<Path> outputs =
        ImmutableList.of(
                Objects.requireNonNull(staticLib.getSourcePathToOutput()),
                Objects.requireNonNull(staticPicLib.getSourcePathToOutput()),
                Objects.requireNonNull(sharedLib.getSourcePathToOutput()))
            .stream()
            .map(pathResolver::getRelativePath)
            .collect(ImmutableList.toImmutableList());
    assertThat(outputs.size(), Matchers.equalTo(ImmutableSet.copyOf(outputs).size()));

    ImmutableList<BuildTarget> targets =
        ImmutableList.of(
            staticLib.getBuildTarget(), staticPicLib.getBuildTarget(), sharedLib.getBuildTarget());
    assertThat(targets.size(), Matchers.equalTo(ImmutableSet.copyOf(targets).size()));
  }

  @Test
  public void linkWhole() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    HaskellLibraryBuilder builder = new HaskellLibraryBuilder(target).setLinkWhole(true);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(builder.build()));
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    HaskellLibrary library = builder.build(graphBuilder);

    // Lookup the link whole flags.
    Linker linker =
        CxxPlatformUtils.DEFAULT_PLATFORM
            .getLd()
            .resolve(graphBuilder, EmptyTargetConfiguration.INSTANCE);
    ImmutableList<String> linkWholeFlags =
        FluentIterable.from(linker.linkWhole(StringArg.of("sentinel"), pathResolver))
            .transformAndConcat((input) -> Arg.stringifyList(input, pathResolver))
            .filter(Predicates.not("sentinel"::equals))
            .toList();

    // Test static dep type.
    NativeLinkableInput staticInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE);
    assertThat(
        Arg.stringify(staticInput.getArgs(), pathResolver),
        hasItems(linkWholeFlags.toArray(new String[0])));

    // Test static-pic dep type.
    NativeLinkableInput staticPicInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE);
    assertThat(
        Arg.stringify(staticPicInput.getArgs(), pathResolver),
        hasItems(linkWholeFlags.toArray(new String[0])));

    // Test shared dep type.
    NativeLinkableInput sharedInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE);
    assertThat(
        Arg.stringify(sharedInput.getArgs(), pathResolver),
        not(hasItems(linkWholeFlags.toArray(new String[0]))));
  }

  @Test
  public void preferredLinkage() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(TargetGraphFactory.newInstance());

    // Test default value.
    HaskellLibrary defaultLib =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:default")).build(graphBuilder);
    assertThat(
        defaultLib.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.is(NativeLinkable.Linkage.ANY));

    // Test `ANY` value.
    HaskellLibrary anyLib =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:any"))
            .setPreferredLinkage(NativeLinkable.Linkage.ANY)
            .build(graphBuilder);
    assertThat(
        anyLib.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.is(NativeLinkable.Linkage.ANY));

    // Test `STATIC` value.
    HaskellLibrary staticLib =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:static"))
            .setPreferredLinkage(NativeLinkable.Linkage.STATIC)
            .build(graphBuilder);
    assertThat(
        staticLib.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.is(NativeLinkable.Linkage.STATIC));

    // Test `SHARED` value.
    HaskellLibrary sharedLib =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:shared"))
            .setPreferredLinkage(NativeLinkable.Linkage.SHARED)
            .build(graphBuilder);
    assertThat(
        sharedLib.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.is(NativeLinkable.Linkage.SHARED));
  }

  @Test
  public void thinArchivesPropagatesDepFromObjects() {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.DEFAULT_PLATFORM.withArchiveContents(ArchiveContents.THIN);
    HaskellPlatform haskellPlatform =
        HaskellTestUtils.DEFAULT_PLATFORM.withCxxPlatform(cxxPlatform);
    FlavorDomain<HaskellPlatform> haskellPlatforms =
        FlavorDomain.of("Haskell Platforms", haskellPlatform);

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    HaskellLibraryBuilder builder =
        new HaskellLibraryBuilder(
                target,
                haskellPlatform,
                haskellPlatforms,
                new CxxBuckConfig(FakeBuckConfig.builder().build()))
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("Test.hs"))))
            .setLinkWhole(true);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(builder.build()));
    HaskellLibrary library = builder.build(graphBuilder);

    // Test static dep type.
    NativeLinkableInput staticInput =
        library.getNativeLinkableInput(
            cxxPlatform,
            Linker.LinkableDepType.STATIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE);
    assertThat(
        FluentIterable.from(staticInput.getArgs())
            .transformAndConcat(
                arg ->
                    BuildableSupport.getDepsCollection(arg, new SourcePathRuleFinder(graphBuilder)))
            .transform(BuildRule::getBuildTarget)
            .toList(),
        Matchers.hasItem(
            HaskellDescriptionUtils.getCompileBuildTarget(
                library.getBuildTarget(), haskellPlatform, Linker.LinkableDepType.STATIC, false)));
  }

  @Test
  public void platformDeps() {
    HaskellLibraryBuilder depABuilder =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:depA"));
    HaskellLibraryBuilder depBBuilder =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:depB"));
    HaskellLibraryBuilder ruleBuilder =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(
                            CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR.toString(), Pattern.LITERAL),
                        ImmutableSortedSet.of(depABuilder.getTarget()))
                    .add(
                        Pattern.compile("matches nothing", Pattern.LITERAL),
                        ImmutableSortedSet.of(depBBuilder.getTarget()))
                    .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            depABuilder.build(), depBBuilder.build(), ruleBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    HaskellLibrary depA = (HaskellLibrary) graphBuilder.requireRule(depABuilder.getTarget());
    HaskellLibrary depB = (HaskellLibrary) graphBuilder.requireRule(depBBuilder.getTarget());
    HaskellLibrary rule = (HaskellLibrary) graphBuilder.requireRule(ruleBuilder.getTarget());
    assertThat(
        rule.getCompileDeps(HaskellTestUtils.DEFAULT_PLATFORM),
        Matchers.allOf(Matchers.hasItem(depA), not(Matchers.hasItem(depB))));
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableExportedDepsForPlatform(
                CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)),
        Matchers.allOf(Matchers.hasItem(depA), not(Matchers.hasItem(depB))));
    assertThat(
        rule.getCxxPreprocessorDeps(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder),
        Matchers.allOf(Matchers.hasItem(depA), not(Matchers.hasItem(depB))));
  }

  @Test
  public void defaultPlatform() {
    HaskellPlatform ruleDefaultPlatform =
        HaskellTestUtils.DEFAULT_PLATFORM.withCxxPlatform(
            HaskellTestUtils.DEFAULT_PLATFORM
                .getCxxPlatform()
                .withFlavor(InternalFlavor.of("custom_platform")));
    HaskellLibraryBuilder libBuilder =
        new HaskellLibraryBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            HaskellTestUtils.DEFAULT_PLATFORM,
            FlavorDomain.of(
                "Haskell Platform", HaskellTestUtils.DEFAULT_PLATFORM, ruleDefaultPlatform),
            CxxPlatformUtils.DEFAULT_CONFIG);
    libBuilder.setPlatform(ruleDefaultPlatform.getFlavor());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    HaskellHaddockLibRule rule =
        (HaskellHaddockLibRule)
            graphBuilder.requireRule(
                libBuilder
                    .getTarget()
                    .withAppendedFlavors(HaskellLibraryDescription.Type.HADDOCK.getFlavor()));
    assertThat(rule.getPlatform(), Matchers.equalTo(ruleDefaultPlatform));
  }
}
