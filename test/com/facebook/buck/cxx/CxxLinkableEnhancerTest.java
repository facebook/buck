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

import static com.facebook.buck.testutil.HasConsecutiveItemsMatcher.hasConsecutiveItems;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.Test;

public class CxxLinkableEnhancerTest {

  private static final Path DEFAULT_OUTPUT = Paths.get("libblah.a");
  private static final ImmutableList<Arg> DEFAULT_INPUTS =
      SourcePathArg.from(
          FakeSourcePath.of("a.o"), FakeSourcePath.of("b.o"), FakeSourcePath.of("c.o"));
  private static final ImmutableSortedSet<NativeLinkable> EMPTY_DEPS = ImmutableSortedSet.of();
  private static final CxxPlatform CXX_PLATFORM =
      CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

  private static class FakeNativeLinkable extends FakeBuildRule implements NativeLinkable {

    private final NativeLinkableInput staticInput;
    private final NativeLinkableInput sharedInput;

    public FakeNativeLinkable(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        NativeLinkableInput staticInput,
        NativeLinkableInput sharedInput) {
      super(buildTarget, projectFilesystem, params);
      this.staticInput = Objects.requireNonNull(staticInput);
      this.sharedInput = Objects.requireNonNull(sharedInput);
    }

    @Override
    public Iterable<NativeLinkable> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
      return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
    }

    @Override
    public Iterable<NativeLinkable> getNativeLinkableExportedDeps(BuildRuleResolver ruleResolver) {
      return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput(
        CxxPlatform cxxPlatform,
        LinkableDepType type,
        boolean forceLinkWhole,
        ActionGraphBuilder graphBuilder,
        TargetConfiguration targetConfiguration) {
      return type == Linker.LinkableDepType.STATIC ? staticInput : sharedInput;
    }

    @Override
    public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
      return Linkage.ANY;
    }

    @Override
    public ImmutableMap<String, SourcePath> getSharedLibraries(
        CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
      return ImmutableMap.of();
    }
  }

  private static FakeNativeLinkable createNativeLinkable(
      String target,
      NativeLinkableInput staticNativeLinkableInput,
      NativeLinkableInput sharedNativeLinkableInput,
      BuildRule... deps) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance(target);
    return new FakeNativeLinkable(
        buildTarget,
        new FakeProjectFilesystem(),
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.copyOf(deps)),
        staticNativeLinkableInput,
        sharedNativeLinkableInput);
  }

  @Test
  public void testThatBuildTargetSourcePathDepsAndPathsArePropagated() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");

    // Create a couple of genrules to generate inputs for an archive rule.
    Genrule genrule1 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setOut("foo/bar.o")
            .build(graphBuilder);
    Genrule genrule2 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule2"))
            .setOut("foo/test.o")
            .build(graphBuilder);

    // Build the archive using a normal input the outputs of the genrules above.
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    CxxLink cxxLink =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            CxxPlatformUtils.DEFAULT_CONFIG,
            CXX_PLATFORM,
            projectFilesystem,
            graphBuilder,
            DefaultSourcePathResolver.from(ruleFinder),
            ruleFinder,
            target,
            Linker.LinkType.EXECUTABLE,
            Optional.empty(),
            DEFAULT_OUTPUT,
            ImmutableList.of(),
            Linker.LinkableDepType.STATIC,
            CxxLinkOptions.of(),
            EMPTY_DEPS,
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            NativeLinkableInput.builder()
                .setArgs(
                    SourcePathArg.from(
                        FakeSourcePath.of("simple.o"),
                        genrule1.getSourcePathToOutput(),
                        genrule2.getSourcePathToOutput()))
                .build(),
            Optional.empty(),
            TestCellPathResolver.get(projectFilesystem));

    // Verify that the archive dependencies include the genrules providing the
    // SourcePath inputs.
    assertEquals(ImmutableSortedSet.<BuildRule>of(genrule1, genrule2), cxxLink.getBuildDeps());
  }

  @Test
  public void testThatBuildTargetsFromNativeLinkableDepsContributeToActualDeps() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");

    // Create a dummy build rule and add it to the graphBuilder.
    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//:fake");
    FakeBuildRule fakeBuildRule =
        new FakeBuildRule(
            fakeBuildTarget, new FakeProjectFilesystem(), TestBuildRuleParams.create());
    fakeBuildRule.setOutputFile("foo");
    graphBuilder.addToIndex(fakeBuildRule);

    // Create a native linkable dep and have it list the fake build rule above as a link
    // time dependency.
    NativeLinkableInput nativeLinkableInput =
        NativeLinkableInput.of(
            ImmutableList.of(SourcePathArg.of(fakeBuildRule.getSourcePathToOutput())),
            ImmutableSet.of(),
            ImmutableSet.of());
    FakeNativeLinkable nativeLinkable =
        createNativeLinkable("//:dep", nativeLinkableInput, nativeLinkableInput);

    // Construct a CxxLink object and pass the native linkable above as the dep.
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    CxxLink cxxLink =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            CxxPlatformUtils.DEFAULT_CONFIG,
            CXX_PLATFORM,
            projectFilesystem,
            graphBuilder,
            pathResolver,
            ruleFinder,
            target,
            Linker.LinkType.EXECUTABLE,
            Optional.empty(),
            DEFAULT_OUTPUT,
            ImmutableList.of(),
            Linker.LinkableDepType.STATIC,
            CxxLinkOptions.of(),
            ImmutableList.<NativeLinkable>of(nativeLinkable),
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            NativeLinkableInput.builder().setArgs(DEFAULT_INPUTS).build(),
            Optional.empty(),
            TestCellPathResolver.get(projectFilesystem));

    // Verify that the fake build rule made it in as a dep.
    assertTrue(cxxLink.getBuildDeps().contains(fakeBuildRule));
  }

  @Test
  public void createCxxLinkableBuildRuleExecutableVsShared() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    String soname = "soname";
    ImmutableList<String> sonameArgs =
        ImmutableList.copyOf(
            CXX_PLATFORM
                .getLd()
                .resolve(graphBuilder, EmptyTargetConfiguration.INSTANCE)
                .soname(soname));

    // Construct a CxxLink object which links as an executable.
    CxxLink executable =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            CxxPlatformUtils.DEFAULT_CONFIG,
            CXX_PLATFORM,
            filesystem,
            graphBuilder,
            pathResolver,
            ruleFinder,
            target,
            Linker.LinkType.EXECUTABLE,
            Optional.empty(),
            DEFAULT_OUTPUT,
            ImmutableList.of(),
            Linker.LinkableDepType.STATIC,
            CxxLinkOptions.of(),
            EMPTY_DEPS,
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            NativeLinkableInput.builder().setArgs(DEFAULT_INPUTS).build(),
            Optional.empty(),
            TestCellPathResolver.get(filesystem));
    assertFalse(executable.getArgs().contains(StringArg.of("-shared")));
    assertEquals(Collections.indexOfSubList(executable.getArgs(), sonameArgs), -1);

    // Construct a CxxLink object which links as a shared lib.
    CxxLink shared =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            CxxPlatformUtils.DEFAULT_CONFIG,
            CXX_PLATFORM,
            filesystem,
            graphBuilder,
            pathResolver,
            ruleFinder,
            target,
            Linker.LinkType.SHARED,
            Optional.empty(),
            DEFAULT_OUTPUT,
            ImmutableList.of(),
            Linker.LinkableDepType.STATIC,
            CxxLinkOptions.of(),
            EMPTY_DEPS,
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            NativeLinkableInput.builder().setArgs(DEFAULT_INPUTS).build(),
            Optional.empty(),
            TestCellPathResolver.get(filesystem));
    assertTrue(Arg.stringify(shared.getArgs(), pathResolver).contains("-shared"));
    assertEquals(Collections.indexOfSubList(shared.getArgs(), sonameArgs), -1);

    // Construct a CxxLink object which links as a shared lib with a SONAME.
    CxxLink sharedWithSoname =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            CxxPlatformUtils.DEFAULT_CONFIG,
            CXX_PLATFORM,
            filesystem,
            graphBuilder,
            pathResolver,
            ruleFinder,
            target,
            Linker.LinkType.SHARED,
            Optional.of("soname"),
            DEFAULT_OUTPUT,
            ImmutableList.of(),
            Linker.LinkableDepType.STATIC,
            CxxLinkOptions.of(),
            EMPTY_DEPS,
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            NativeLinkableInput.builder().setArgs(DEFAULT_INPUTS).build(),
            Optional.empty(),
            TestCellPathResolver.get(filesystem));
    ImmutableList<String> args = Arg.stringify(sharedWithSoname.getArgs(), pathResolver);
    assertTrue(args.contains("-shared"));
    assertNotEquals(Collections.indexOfSubList(args, sonameArgs), -1);
  }

  @Test
  public void createCxxLinkableBuildRuleStaticVsSharedDeps() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Create a native linkable dep and have it list the fake build rule above as a link
    // time dependency
    String staticArg = "static";
    NativeLinkableInput staticInput =
        NativeLinkableInput.of(
            ImmutableList.of(StringArg.of(staticArg)), ImmutableSet.of(), ImmutableSet.of());
    String sharedArg = "shared";
    NativeLinkableInput sharedInput =
        NativeLinkableInput.of(
            ImmutableList.of(StringArg.of(sharedArg)), ImmutableSet.of(), ImmutableSet.of());
    FakeNativeLinkable nativeLinkable = createNativeLinkable("//:dep", staticInput, sharedInput);

    // Construct a CxxLink object which links using static dependencies.
    CxxLink staticLink =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            CxxPlatformUtils.DEFAULT_CONFIG,
            CXX_PLATFORM,
            filesystem,
            graphBuilder,
            pathResolver,
            ruleFinder,
            target,
            Linker.LinkType.EXECUTABLE,
            Optional.empty(),
            DEFAULT_OUTPUT,
            ImmutableList.of(),
            Linker.LinkableDepType.STATIC,
            CxxLinkOptions.of(),
            ImmutableList.<NativeLinkable>of(nativeLinkable),
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            NativeLinkableInput.builder().setArgs(DEFAULT_INPUTS).build(),
            Optional.empty(),
            TestCellPathResolver.get(filesystem));
    ImmutableList<String> args = Arg.stringify(staticLink.getArgs(), pathResolver);
    assertTrue(args.contains(staticArg) || args.contains("-Wl," + staticArg));
    assertFalse(args.contains(sharedArg));
    assertFalse(args.contains("-Wl," + sharedArg));

    // Construct a CxxLink object which links using shared dependencies.
    CxxLink sharedLink =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            CxxPlatformUtils.DEFAULT_CONFIG,
            CXX_PLATFORM,
            filesystem,
            graphBuilder,
            pathResolver,
            ruleFinder,
            target,
            Linker.LinkType.EXECUTABLE,
            Optional.empty(),
            DEFAULT_OUTPUT,
            ImmutableList.of(),
            Linker.LinkableDepType.SHARED,
            CxxLinkOptions.of(),
            ImmutableList.<NativeLinkable>of(nativeLinkable),
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            NativeLinkableInput.builder().setArgs(DEFAULT_INPUTS).build(),
            Optional.empty(),
            TestCellPathResolver.get(filesystem));
    args = Arg.stringify(sharedLink.getArgs(), pathResolver);
    assertFalse(args.contains(staticArg));
    assertFalse(args.contains("-Wl," + staticArg));
    assertTrue(args.contains(sharedArg) || args.contains("-Wl," + sharedArg));
  }

  @Test
  public void platformLdFlags() {
    ImmutableMap<Linker.LinkableDepType, String> runtimes =
        ImmutableMap.of(
            Linker.LinkableDepType.SHARED, "-ldummy-shared-libc",
            Linker.LinkableDepType.STATIC, "-ldummy-static-libc",
            Linker.LinkableDepType.STATIC_PIC, "-ldummy-static-pic-libc");
    CxxPlatform cxxPlatform =
        CxxPlatform.builder()
            .from(CXX_PLATFORM)
            .putAllRuntimeLdflags(runtimes.asMultimap())
            .build();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    for (Map.Entry<Linker.LinkableDepType, String> ent : runtimes.entrySet()) {
      FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
      CxxLink lib =
          CxxLinkableEnhancer.createCxxLinkableBuildRule(
              CxxPlatformUtils.DEFAULT_CONFIG,
              cxxPlatform,
              filesystem,
              graphBuilder,
              pathResolver,
              ruleFinder,
              target,
              Linker.LinkType.SHARED,
              Optional.empty(),
              DEFAULT_OUTPUT,
              ImmutableList.of(),
              ent.getKey(),
              CxxLinkOptions.of(),
              EMPTY_DEPS,
              Optional.empty(),
              Optional.empty(),
              ImmutableSet.of(),
              ImmutableSet.of(),
              NativeLinkableInput.builder().setArgs(DEFAULT_INPUTS).build(),
              Optional.empty(),
              TestCellPathResolver.get(filesystem));
      assertThat(Arg.stringify(lib.getArgs(), pathResolver), hasItem(ent.getValue()));
    }
  }

  @Test
  public void getTransitiveNativeLinkableInputDoesNotTraversePastNonNativeLinkables() {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    // Create a native linkable that sits at the bottom of the dep chain.
    String sentinel = "bottom";
    NativeLinkableInput bottomInput =
        NativeLinkableInput.of(
            ImmutableList.of(StringArg.of(sentinel)), ImmutableSet.of(), ImmutableSet.of());
    BuildRule bottom = createNativeLinkable("//:bottom", bottomInput, bottomInput);

    // Create a non-native linkable that sits in the middle of the dep chain, preventing
    // traversals to the bottom native linkable.
    BuildRule middle = new FakeBuildRule("//:middle", bottom);

    // Create a native linkable that sits at the top of the dep chain.
    NativeLinkableInput topInput =
        NativeLinkableInput.of(ImmutableList.of(), ImmutableSet.of(), ImmutableSet.of());
    BuildRule top = createNativeLinkable("//:top", topInput, topInput, middle);

    // Now grab all input via traversing deps and verify that the middle rule prevents pulling
    // in the bottom input.
    NativeLinkableInput totalInput =
        NativeLinkables.getTransitiveNativeLinkableInput(
            cxxPlatform,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE,
            ImmutableList.of(top),
            Linker.LinkableDepType.STATIC,
            r -> Optional.empty());
    assertThat(Arg.stringify(bottomInput.getArgs(), pathResolver), hasItem(sentinel));
    assertThat(Arg.stringify(totalInput.getArgs(), pathResolver), not(hasItem(sentinel)));
  }

  @Test
  public void machOBundleWithBundleLoaderHasExpectedArgs() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    CxxLink cxxLink =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            CxxPlatformUtils.DEFAULT_CONFIG,
            CXX_PLATFORM,
            filesystem,
            graphBuilder,
            DefaultSourcePathResolver.from(ruleFinder),
            ruleFinder,
            target,
            Linker.LinkType.MACH_O_BUNDLE,
            Optional.empty(),
            DEFAULT_OUTPUT,
            ImmutableList.of(),
            Linker.LinkableDepType.STATIC,
            CxxLinkOptions.of(),
            EMPTY_DEPS,
            Optional.empty(),
            Optional.of(FakeSourcePath.of(filesystem, "path/to/MyBundleLoader")),
            ImmutableSet.of(),
            ImmutableSet.of(),
            NativeLinkableInput.builder()
                .setArgs(SourcePathArg.from(FakeSourcePath.of("simple.o")))
                .build(),
            Optional.empty(),
            TestCellPathResolver.get(filesystem));
    assertThat(Arg.stringify(cxxLink.getArgs(), pathResolver), hasItem("-bundle"));
    assertThat(
        Arg.stringify(cxxLink.getArgs(), pathResolver),
        hasConsecutiveItems(
            "-bundle_loader", filesystem.resolve("path/to/MyBundleLoader").toString()));
  }

  @Test
  public void machOBundleSourcePathIsInDepsOfRule() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget bundleLoaderTarget = BuildTargetFactory.newInstance("//foo:bundleLoader");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    CxxLink bundleLoaderRule =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            CxxPlatformUtils.DEFAULT_CONFIG,
            CXX_PLATFORM,
            filesystem,
            graphBuilder,
            DefaultSourcePathResolver.from(ruleFinder),
            ruleFinder,
            bundleLoaderTarget,
            Linker.LinkType.EXECUTABLE,
            Optional.empty(),
            DEFAULT_OUTPUT,
            ImmutableList.of(),
            Linker.LinkableDepType.STATIC,
            CxxLinkOptions.of(),
            EMPTY_DEPS,
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            NativeLinkableInput.builder()
                .setArgs(SourcePathArg.from(FakeSourcePath.of("simple.o")))
                .build(),
            Optional.empty(),
            TestCellPathResolver.get(filesystem));
    graphBuilder.addToIndex(bundleLoaderRule);

    BuildTarget bundleTarget = BuildTargetFactory.newInstance("//foo:bundle");
    CxxLink bundleRule =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            CxxPlatformUtils.DEFAULT_CONFIG,
            CXX_PLATFORM,
            filesystem,
            graphBuilder,
            DefaultSourcePathResolver.from(ruleFinder),
            ruleFinder,
            bundleTarget,
            Linker.LinkType.MACH_O_BUNDLE,
            Optional.empty(),
            DEFAULT_OUTPUT,
            ImmutableList.of(),
            Linker.LinkableDepType.STATIC,
            CxxLinkOptions.of(),
            EMPTY_DEPS,
            Optional.empty(),
            Optional.of(bundleLoaderRule.getSourcePathToOutput()),
            ImmutableSet.of(),
            ImmutableSet.of(),
            NativeLinkableInput.builder()
                .setArgs(SourcePathArg.from(FakeSourcePath.of("another.o")))
                .build(),
            Optional.empty(),
            TestCellPathResolver.get(filesystem));

    // Ensure the bundle depends on the bundle loader rule.
    assertThat(bundleRule.getBuildDeps(), hasItem(bundleLoaderRule));
  }

  @Test
  public void frameworksToLinkerFlagsTransformer() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));

    Arg linkerFlags =
        CxxLinkableEnhancer.frameworksToLinkerArg(
            ImmutableSortedSet.of(
                FrameworkPath.ofSourceTreePath(
                    new SourceTreePath(
                        PBXReference.SourceTree.DEVELOPER_DIR,
                        Paths.get("Library/Frameworks/XCTest.framework"),
                        Optional.empty())),
                FrameworkPath.ofSourcePath(
                    FakeSourcePath.of(projectFilesystem, "Vendor/Bar/Bar.framework"))));

    assertEquals(
        ImmutableList.of(
            "-framework", "XCTest",
            "-framework", "Bar"),
        Arg.stringifyList(linkerFlags, resolver));
  }
}
