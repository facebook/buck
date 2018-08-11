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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxToolProvider;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.PreprocessorProvider;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CxxPrecompiledHeaderRuleTest {

  private static final CxxBuckConfig CXX_CONFIG_PCH_ENABLED =
      new CxxBuckConfig(FakeBuckConfig.builder().setSections("[cxx]", "pch_enabled=true").build());

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectFilesystem filesystem;
  private ProjectWorkspace workspace;

  private PreprocessorProvider preprocessorSupportingPch;
  private CxxPlatform platformSupportingPch;
  private CxxBuckConfig cxxConfigPchDisabled;
  private CxxPlatform platformNotSupportingPch;

  @Before
  public void setUp() throws InterruptedException, IOException {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();

    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_precompiled_header_rule", tmp);
    workspace.setUp();

    preprocessorSupportingPch =
        new PreprocessorProvider(
            PathSourcePath.of(filesystem, Paths.get("foopp")),
            Optional.of(CxxToolProvider.Type.CLANG));

    platformSupportingPch =
        CxxPlatformUtils.build(CXX_CONFIG_PCH_ENABLED).withCpp(preprocessorSupportingPch);

    cxxConfigPchDisabled =
        new CxxBuckConfig(
            FakeBuckConfig.builder().setSections("[cxx]\n" + "pch_enabled=false\n").build());

    platformNotSupportingPch =
        CxxPlatformUtils.build(cxxConfigPchDisabled).withCpp(preprocessorSupportingPch);
  }

  public final TargetNodeToBuildRuleTransformer transformer =
      new DefaultTargetNodeToBuildRuleTransformer();

  public final ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(transformer);
  public final SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
  public final SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
  public final BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);

  public final Compiler compiler = CxxPlatformUtils.DEFAULT_PLATFORM.getCxx().resolve(graphBuilder);

  public BuildTarget newTarget(String fullyQualifiedName) {
    return BuildTargetFactory.newInstance(fullyQualifiedName);
  }

  public BuildRuleParams newParams() {
    return TestBuildRuleParams.create();
  }

  /** Note: creates the {@link CxxPrecompiledHeaderTemplate}, add to graphBuilder index. */
  public CxxPrecompiledHeaderTemplate newPCH(
      BuildTarget target, SourcePath headerSourcePath, ImmutableSortedSet<BuildRule> deps) {
    return new CxxPrecompiledHeaderTemplate(
        target,
        new FakeProjectFilesystem(),
        deps,
        headerSourcePath,
        pathResolver.getAbsolutePath(headerSourcePath));
  }

  public CxxSource.Builder newCxxSourceBuilder() {
    return CxxSource.builder().setType(CxxSource.Type.C);
  }

  public CxxSource newSource(String filename) {
    return newCxxSourceBuilder().setPath(FakeSourcePath.of(filename)).build();
  }

  public CxxSource newSource() {
    return newSource("foo.cpp");
  }

  public CxxSourceRuleFactory.Builder newFactoryBuilder(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    return CxxSourceRuleFactory.builder()
        .setBaseBuildTarget(buildTarget)
        .setProjectFilesystem(projectFilesystem)
        .setActionGraphBuilder(graphBuilder)
        .setRuleFinder(ruleFinder)
        .setPathResolver(DefaultSourcePathResolver.from(ruleFinder))
        .setCxxPlatform(platformSupportingPch)
        .setPicType(PicType.PIC)
        .setCxxBuckConfig(CXX_CONFIG_PCH_ENABLED);
  }

  public CxxSourceRuleFactory.Builder newFactoryBuilder(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem, String flag) {
    return newFactoryBuilder(buildTarget, projectFilesystem)
        .setCxxPreprocessorInput(
            ImmutableList.of(
                CxxPreprocessorInput.builder()
                    .setPreprocessorFlags(
                        ImmutableMultimap.of(CxxSource.Type.C, StringArg.of(flag)))
                    .build()));
  }

  private CxxPrecompiledHeaderTemplate newPCH(BuildTarget pchTarget) {
    return newPCH(pchTarget, FakeSourcePath.of("header.h"), /* deps */ ImmutableSortedSet.of());
  }

  /** Return the sublist, starting at {@code toFind}, or empty list if not found. */
  List<String> seek(List<String> immList, String toFind) {
    ArrayList<String> list = new ArrayList<>(immList.size());
    list.addAll(immList);
    int i;
    for (i = 0; i < list.size(); i++) {
      if (list.get(i).equals(toFind)) {
        break;
      }
    }
    return list.subList(i, list.size());
  }

  /** @return exit code from that process */
  private int runBuiltBinary(String binaryTarget) throws Exception {
    return workspace
        .runCommand(
            workspace
                .resolve(
                    BuildTargetPaths.getGenPath(
                        filesystem, workspace.newBuildTarget(binaryTarget), "%s"))
                .toString())
        .getExitCode();
  }

  /** Stolen from {@link PrecompiledHeaderIntegrationTest} */
  private static Matcher<BuckBuildLog> reportedTargetSuccessType(
      BuildTarget target, BuildRuleSuccessType successType) {
    return new CustomTypeSafeMatcher<BuckBuildLog>(
        "target: " + target + " with result: " + successType) {

      @Override
      protected boolean matchesSafely(BuckBuildLog buckBuildLog) {
        return buckBuildLog.getLogEntry(target).getSuccessType().equals(Optional.of(successType));
      }
    };
  }

  /** Stolen from {@link PrecompiledHeaderIntegrationTest} */
  private BuildTarget findPchTarget() throws IOException {
    for (BuildTarget target : workspace.getBuildLog().getAllTargets()) {
      for (Flavor flavor : target.getFlavors()) {
        if (flavor.getName().startsWith("pch-")) {
          return target;
        }
      }
    }
    fail("should have generated a pch target");
    return null;
  }

  @Test
  public void samePchIffSameFlags() {
    BuildTarget pchTarget = newTarget("//test:pch");
    CxxPrecompiledHeaderTemplate pch = newPCH(pchTarget);
    graphBuilder.addToIndex(pch);

    BuildTarget lib1Target = newTarget("//test:lib1");
    CxxSourceRuleFactory factory1 =
        newFactoryBuilder(lib1Target, new FakeProjectFilesystem(), "-frtti")
            .setPrecompiledHeader(DefaultBuildTargetSourcePath.of(pchTarget))
            .build();
    CxxPreprocessAndCompile lib1 =
        factory1.requirePreprocessAndCompileBuildRule("lib1.cpp", newSource("lib1.cpp"));
    graphBuilder.addToIndex(lib1);
    ImmutableList<String> cmd1 = lib1.makeMainStep(context, false).getCommand();

    BuildTarget lib2Target = newTarget("//test:lib2");
    CxxSourceRuleFactory factory2 =
        newFactoryBuilder(lib2Target, new FakeProjectFilesystem(), "-frtti")
            .setPrecompiledHeader(DefaultBuildTargetSourcePath.of(pchTarget))
            .build();
    CxxPreprocessAndCompile lib2 =
        factory2.requirePreprocessAndCompileBuildRule("lib2.cpp", newSource("lib2.cpp"));
    graphBuilder.addToIndex(lib2);
    ImmutableList<String> cmd2 = lib2.makeMainStep(context, false).getCommand();

    BuildTarget lib3Target = newTarget("//test:lib3");
    CxxSourceRuleFactory factory3 =
        newFactoryBuilder(lib3Target, new FakeProjectFilesystem(), "-fno-rtti")
            .setPrecompiledHeader(DefaultBuildTargetSourcePath.of(pchTarget))
            .build();
    CxxPreprocessAndCompile lib3 =
        factory3.requirePreprocessAndCompileBuildRule("lib3.cpp", newSource("lib3.cpp"));
    graphBuilder.addToIndex(lib3);
    ImmutableList<String> cmd3 = lib3.makeMainStep(context, false).getCommand();

    assertTrue(seek(cmd1, "-frtti").size() > 0);
    assertTrue(seek(cmd2, "-frtti").size() > 0);
    assertFalse(seek(cmd3, "-frtti").size() > 0);

    assertFalse(seek(cmd1, "-fno-rtti").size() > 0);
    assertFalse(seek(cmd2, "-fno-rtti").size() > 0);
    assertTrue(seek(cmd3, "-fno-rtti").size() > 0);

    List<String> pchFlag1 = seek(cmd1, "-include-pch");
    assertTrue(pchFlag1.size() >= 2);
    pchFlag1 = pchFlag1.subList(0, 2);

    List<String> pchFlag2 = seek(cmd2, "-include-pch");
    assertTrue(pchFlag2.size() >= 2);
    pchFlag2 = pchFlag2.subList(0, 2);

    List<String> pchFlag3 = seek(cmd3, "-include-pch");
    assertTrue(pchFlag3.size() >= 2);
    pchFlag3 = pchFlag3.subList(0, 2);

    assertEquals(pchFlag1, pchFlag2);
    assertNotEquals(pchFlag2, pchFlag3);
  }

  @Test
  public void userRuleChangesDependencyPCHRuleFlags() {
    BuildTarget pchTarget = newTarget("//test:pch");
    CxxPrecompiledHeaderTemplate pch = newPCH(pchTarget);
    graphBuilder.addToIndex(pch);

    BuildTarget libTarget = newTarget("//test:lib");
    CxxSourceRuleFactory factory1 =
        newFactoryBuilder(libTarget, new FakeProjectFilesystem(), "-flag-for-factory")
            .setPrecompiledHeader(DefaultBuildTargetSourcePath.of(pchTarget))
            .build();
    CxxPreprocessAndCompile lib =
        factory1.requirePreprocessAndCompileBuildRule(
            "lib.cpp",
            newCxxSourceBuilder()
                .setPath(FakeSourcePath.of("lib.cpp"))
                .setFlags(ImmutableList.of("-flag-for-source"))
                .build());
    graphBuilder.addToIndex(lib);
    ImmutableList<String> libCmd = lib.makeMainStep(context, false).getCommand();
    assertTrue(seek(libCmd, "-flag-for-source").size() > 0);
    assertTrue(seek(libCmd, "-flag-for-factory").size() > 0);

    CxxPrecompiledHeader pchInstance = null;
    for (BuildRule dep : lib.getBuildDeps()) {
      if (dep instanceof CxxPrecompiledHeader) {
        pchInstance = (CxxPrecompiledHeader) dep;
      }
    }
    assertNotNull(pchInstance);
    ImmutableList<String> pchCmd =
        pchInstance.makeMainStep(context, Paths.get("/tmp/x")).getCommand();

    assertTrue(seek(pchCmd, "-flag-for-source").size() > 0);
    assertTrue(seek(pchCmd, "-flag-for-factory").size() > 0);
  }

  @Test
  public void pchDepsNotRepeatedInLinkArgs() {
    BuildTarget publicHeaderTarget = BuildTargetFactory.newInstance("//test:header");
    BuildTarget publicHeaderSymlinkTreeTarget = BuildTargetFactory.newInstance("//test:symlink");
    BuildTarget privateHeaderTarget = BuildTargetFactory.newInstance("//test:privateheader");
    BuildTarget privateHeaderSymlinkTreeTarget =
        BuildTargetFactory.newInstance("//test:privatesymlink");

    graphBuilder.addToIndex(new FakeBuildRule(publicHeaderTarget));
    graphBuilder.addToIndex(new FakeBuildRule(publicHeaderSymlinkTreeTarget));
    graphBuilder.addToIndex(new FakeBuildRule(privateHeaderTarget));
    graphBuilder.addToIndex(new FakeBuildRule(privateHeaderSymlinkTreeTarget));

    BuildRuleParams libParams = TestBuildRuleParams.create();
    BuildRule liba =
        graphBuilder.addToIndex(
            new FakeBuildRule("//test:liba").setOutputFile(Paths.get("lib.a").toString()));
    BuildRule libso =
        graphBuilder.addToIndex(
            new FakeBuildRule("//test:libso").setOutputFile(Paths.get("lib.so").toString()));
    BuildTarget libTarget = BuildTargetFactory.newInstance("//test:lib");
    FakeCxxLibrary lib =
        graphBuilder.addToIndex(
            new FakeCxxLibrary(
                libTarget,
                filesystem,
                libParams,
                publicHeaderTarget,
                publicHeaderSymlinkTreeTarget,
                privateHeaderTarget,
                privateHeaderSymlinkTreeTarget,
                liba,
                libso,
                Paths.get("/tmp/lib.so"),
                "lib.so.1",
                ImmutableSortedSet.of()));

    BuildTarget pchTarget = newTarget("//test:pch");
    CxxPrecompiledHeaderTemplate pchTemplate =
        graphBuilder.addToIndex(
            newPCH(
                pchTarget,
                FakeSourcePath.of(
                    filesystem, filesystem.getRootPath().resolve("test/header.h").toString()),
                ImmutableSortedSet.of(lib)));

    BuildTarget binTarget = BuildTargetFactory.newInstance("//test:bin");

    CxxPreprocessAndCompile binBuildRule =
        newFactoryBuilder(binTarget, filesystem)
            .setPrecompiledHeader(DefaultBuildTargetSourcePath.of(pchTarget))
            .build()
            .requirePreprocessAndCompileBuildRule(
                FakeSourcePath.of(filesystem, "test/lib.cpp").toString(),
                newCxxSourceBuilder()
                    .setPath(FakeSourcePath.of(filesystem, "test/bin.cpp"))
                    .build());
    graphBuilder.addToIndex(binBuildRule);

    CxxPrecompiledHeader foundPCH = null;
    for (BuildRule dep : binBuildRule.getBuildDeps()) {
      if (dep instanceof CxxPrecompiledHeader) {
        foundPCH = (CxxPrecompiledHeader) dep;
      }
    }

    assertNotNull(foundPCH);
    CxxPrecompiledHeader pch = foundPCH;

    ImmutableList<SourcePath> binObjects = ImmutableList.of(FakeSourcePath.of(filesystem, "bin.o"));
    ImmutableList<NativeLinkable> nativeLinkableDeps =
        ImmutableList.<NativeLinkable>builder()
            .add(pchTemplate)
            .addAll(
                RichStream.from(pch.getBuildDeps()).filter(NativeLinkable.class).toImmutableList())
            .addAll(
                RichStream.from(lib.getBuildDeps()).filter(NativeLinkable.class).toImmutableList())
            .build();
    CxxLink binLink =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            CXX_CONFIG_PCH_ENABLED,
            platformSupportingPch,
            filesystem,
            graphBuilder,
            pathResolver,
            ruleFinder,
            CxxDescriptionEnhancer.createCxxLinkTarget(
                binTarget, Optional.of(LinkerMapMode.NO_LINKER_MAP)),
            Linker.LinkType.EXECUTABLE,
            Optional.empty(), // soname
            Paths.get("tmp/bin.prog"),
            ImmutableList.of(),
            Linker.LinkableDepType.STATIC,
            CxxLinkOptions.of(),
            nativeLinkableDeps,
            Optional.empty(), // cxxRuntimeType,
            Optional.empty(), // bundleLoader,
            ImmutableSet.of(), // blacklist,
            ImmutableSet.of(libTarget), // linkWholeDeps,
            NativeLinkableInput.builder().addAllArgs(SourcePathArg.from(binObjects)).build(),
            Optional.empty(),
            TestCellPathResolver.get(filesystem));

    CxxWriteArgsToFileStep argsToFile = null;
    for (Step step :
        binLink.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext())) {
      if (step instanceof CxxWriteArgsToFileStep) {
        argsToFile = (CxxWriteArgsToFileStep) step;
        break;
      }
    }
    assertNotNull(argsToFile);

    int libaCount = 0;
    for (String arg : argsToFile.getArgFileContents()) {
      if (arg.equals("lib.a") || arg.endsWith("/lib.a")) {
        ++libaCount;
      }
    }
    assertEquals(1, libaCount);
  }

  private static <T> void assertContains(ImmutableList<T> container, Iterable<T> items) {
    for (T item : items) {
      assertThat(container, Matchers.hasItem(item));
    }
  }

  @Test
  public void pchDisabledShouldIncludeAsRegularHeader() {
    BuildTarget pchTarget = newTarget("//test:pch");
    CxxPrecompiledHeaderTemplate pch =
        newPCH(pchTarget, FakeSourcePath.of("header.h"), ImmutableSortedSet.of());
    graphBuilder.addToIndex(pch);
    CxxPreprocessAndCompile compileLibRule =
        newFactoryBuilder(newTarget("//test:lib"), new FakeProjectFilesystem())
            .setPrecompiledHeader(DefaultBuildTargetSourcePath.of(pchTarget))
            .setCxxPlatform(platformNotSupportingPch)
            .setCxxBuckConfig(cxxConfigPchDisabled)
            .build()
            .requirePreprocessAndCompileBuildRule("lib.cpp", newSource("lib.cpp"));

    graphBuilder.addToIndex(compileLibRule);
    ImmutableList<String> compileLibCmd = compileLibRule.makeMainStep(context, false).getCommand();

    assertSame(seek(compileLibCmd, "-include-pch").size(), 0);

    List<String> flag = seek(compileLibCmd, "-include");
    assertTrue(flag.size() >= 2);
    assertTrue(flag.get(1).endsWith(".h"));
  }

  @Test
  public void userRuleIncludePathsChangedByPCH() {
    CxxPreprocessorInput cxxPreprocessorInput =
        CxxPreprocessorInput.builder()
            .addIncludes(
                CxxHeadersDir.of(
                    CxxPreprocessables.IncludeType.SYSTEM, FakeSourcePath.of("/tmp/sys")))
            .build();

    BuildTarget lib1Target = newTarget("//some/other/dir:lib1");
    CxxSourceRuleFactory lib1Factory =
        newFactoryBuilder(lib1Target, new FakeProjectFilesystem())
            .addCxxPreprocessorInput(cxxPreprocessorInput)
            .build();
    CxxPreprocessAndCompile lib1 =
        lib1Factory.requirePreprocessAndCompileBuildRule("lib1.cpp", newSource("lib1.cpp"));
    graphBuilder.addToIndex(lib1);

    ImmutableList<String> lib1Cmd = lib1.makeMainStep(context, false).getCommand();

    BuildTarget pchTarget = newTarget("//test:pch");
    CxxPrecompiledHeaderTemplate pch =
        newPCH(pchTarget, FakeSourcePath.of("header.h"), ImmutableSortedSet.of(lib1));
    graphBuilder.addToIndex(pch);

    BuildTarget lib2Target = newTarget("//test:lib2");
    CxxSourceRuleFactory lib2Factory =
        newFactoryBuilder(lib2Target, new FakeProjectFilesystem())
            .setPrecompiledHeader(DefaultBuildTargetSourcePath.of(pchTarget))
            .build();
    CxxPreprocessAndCompile lib2 =
        lib2Factory.requirePreprocessAndCompileBuildRule("lib2.cpp", newSource("lib2.cpp"));
    graphBuilder.addToIndex(lib2);
    ImmutableList<String> lib2Cmd = lib2.makeMainStep(context, false).getCommand();

    CxxPrecompiledHeader pchInstance = null;
    for (BuildRule dep : lib2.getBuildDeps()) {
      if (dep instanceof CxxPrecompiledHeader) {
        pchInstance = (CxxPrecompiledHeader) dep;
      }
    }
    assertNotNull(pchInstance);
    ImmutableList<String> pchCmd =
        pchInstance.makeMainStep(context, Paths.get("/tmp/z")).getCommand();

    // (pretend that) lib1 has a dep resulting in adding this dir to the include path flags
    assertContains(lib1Cmd, ImmutableList.of("-isystem", "/tmp/sys"));

    // PCH should inherit those flags
    assertContains(pchCmd, ImmutableList.of("-isystem", "/tmp/sys"));

    // and because PCH uses them, these should be used in lib2 which uses PCH; also, used *first*
    assertContains(lib2Cmd, ImmutableList.of("-isystem", "/tmp/sys"));
    Iterator<String> iter = lib2Cmd.iterator();
    while (iter.hasNext()) {
      if (iter.next().equals("-isystem")) {
        break;
      }
    }
    assertTrue(iter.hasNext());
    assertEquals("/tmp/sys", iter.next());
  }

  @Test
  public void successfulBuildWithPchHavingNoDeps() throws Exception {
    workspace.runBuckBuild("//basic_tests:main").assertSuccess();
  }

  @Test
  public void successfulBuildWithPchHavingDeps() throws Exception {
    workspace.runBuckBuild("//deps_test:bin").assertSuccess();
  }

  @Test
  public void changingPrecompilableHeaderCausesRecompile() throws Exception {
    BuckBuildLog buildLog;

    workspace.writeContentsToPath(
        "#define TESTVALUE 42\n", "recompile_after_header_changed/header.h");
    workspace.runBuckBuild("//recompile_after_header_changed:main#default").assertSuccess();
    buildLog = workspace.getBuildLog();
    assertThat(
        buildLog, reportedTargetSuccessType(findPchTarget(), BuildRuleSuccessType.BUILT_LOCALLY));
    assertThat(
        buildLog,
        reportedTargetSuccessType(
            workspace.newBuildTarget("//recompile_after_header_changed:main#binary,default"),
            BuildRuleSuccessType.BUILT_LOCALLY));
    assertEquals(42, runBuiltBinary("//recompile_after_header_changed:main#default"));

    workspace.resetBuildLogFile();

    workspace.writeContentsToPath(
        "#define TESTVALUE 43\n", "recompile_after_header_changed/header.h");
    workspace.runBuckBuild("//recompile_after_header_changed:main#default").assertSuccess();
    buildLog = workspace.getBuildLog();
    assertThat(
        buildLog, reportedTargetSuccessType(findPchTarget(), BuildRuleSuccessType.BUILT_LOCALLY));
    assertThat(
        buildLog,
        reportedTargetSuccessType(
            workspace.newBuildTarget("//recompile_after_header_changed:main#binary,default"),
            BuildRuleSuccessType.BUILT_LOCALLY));
    assertEquals(43, runBuiltBinary("//recompile_after_header_changed:main#default"));
  }

  @Test
  public void changingHeaderIncludedByPCHPrefixHeaderCausesRecompile() throws Exception {
    BuckBuildLog buildLog;

    workspace.writeContentsToPath(
        "#define TESTVALUE 50\n", "recompile_after_include_changed/included_by_pch.h");
    workspace.runBuckBuild("//recompile_after_include_changed:main#default").assertSuccess();
    buildLog = workspace.getBuildLog();
    assertThat(
        buildLog, reportedTargetSuccessType(findPchTarget(), BuildRuleSuccessType.BUILT_LOCALLY));
    assertThat(
        buildLog,
        reportedTargetSuccessType(
            workspace.newBuildTarget("//recompile_after_include_changed:main#binary,default"),
            BuildRuleSuccessType.BUILT_LOCALLY));
    assertEquals(
        workspace
            .runCommand(
                workspace
                    .resolve(
                        BuildTargetPaths.getGenPath(
                            filesystem,
                            workspace.newBuildTarget(
                                "//recompile_after_include_changed:main#default"),
                            "%s"))
                    .toString())
            .getExitCode(),
        50);

    workspace.resetBuildLogFile();

    workspace.writeContentsToPath(
        "#define TESTVALUE 51\n", "recompile_after_include_changed/included_by_pch.h");
    workspace.runBuckBuild("//recompile_after_include_changed:main#default").assertSuccess();
    buildLog = workspace.getBuildLog();

    assertThat(
        buildLog, reportedTargetSuccessType(findPchTarget(), BuildRuleSuccessType.BUILT_LOCALLY));
    assertThat(
        buildLog,
        reportedTargetSuccessType(
            workspace.newBuildTarget("//recompile_after_include_changed:main#binary,default"),
            BuildRuleSuccessType.BUILT_LOCALLY));
    assertEquals(
        workspace
            .runCommand(
                workspace
                    .resolve(
                        BuildTargetPaths.getGenPath(
                            filesystem,
                            workspace.newBuildTarget(
                                "//recompile_after_include_changed:main#default"),
                            "%s"))
                    .toString())
            .getExitCode(),
        51);
  }

  @Test
  public void deterministicHashesForSharedPCHs() throws Exception {
    Sha1HashCode pchHashA = null;
    workspace.runBuckBuild("//determinism/a:main").assertSuccess();
    BuckBuildLog buildLogA = workspace.getBuildLog();
    for (BuildTarget target : buildLogA.getAllTargets()) {
      if (target.toString().startsWith("//determinism/lib:pch#default,pch-cxx-")) {
        pchHashA = buildLogA.getLogEntry(target).getRuleKey();
        System.err.println("A: " + target + " " + pchHashA);
      }
    }
    assertNotNull(pchHashA);

    Sha1HashCode pchHashB = null;
    workspace.runBuckBuild("//determinism/b:main").assertSuccess();
    BuckBuildLog buildLogB = workspace.getBuildLog();
    for (BuildTarget target : buildLogB.getAllTargets()) {
      if (target.toString().startsWith("//determinism/lib:pch#default,pch-cxx-")) {
        pchHashB = buildLogB.getLogEntry(target).getRuleKey();
        System.err.println("B: " + target + " " + pchHashB);
      }
    }
    assertNotNull(pchHashB);
    assertEquals(pchHashA, pchHashB);

    Sha1HashCode pchHashC = null;
    workspace.runBuckBuild("//determinism/c:main").assertSuccess();
    BuckBuildLog buildLogC = workspace.getBuildLog();
    for (BuildTarget target : buildLogC.getAllTargets()) {
      if (target.toString().startsWith("//determinism/lib:pch#default,pch-cxx-")) {
        pchHashC = buildLogC.getLogEntry(target).getRuleKey();
        System.err.println("C: " + target + " " + pchHashC);
      }
    }
    assertNotNull(pchHashC);
    assertNotEquals(pchHashA, pchHashC);
  }
}
