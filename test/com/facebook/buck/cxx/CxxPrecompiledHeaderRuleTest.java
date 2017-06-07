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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@SuppressWarnings("all")
public class CxxPrecompiledHeaderRuleTest {

  private static final CxxBuckConfig CXX_CONFIG_PCH_ENABLED =
      new CxxBuckConfig(FakeBuckConfig.builder().setSections("[cxx]", "pch_enabled=true").build());

  private static final PreprocessorProvider PREPROCESSOR_SUPPORTING_PCH =
      new PreprocessorProvider(Paths.get("foopp"), Optional.of(CxxToolProvider.Type.CLANG));

  private static final CxxPlatform PLATFORM_SUPPORTING_PCH =
      CxxPlatformUtils.build(CXX_CONFIG_PCH_ENABLED).withCpp(PREPROCESSOR_SUPPORTING_PCH);

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectFilesystem filesystem;
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws InterruptedException, IOException {
    filesystem = new ProjectFilesystem(tmp.getRoot());
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_precompiled_header_rule", tmp);
    workspace.setUp();
  }

  public final TargetNodeToBuildRuleTransformer transformer =
      new DefaultTargetNodeToBuildRuleTransformer();

  public final BuildRuleResolver ruleResolver =
      new BuildRuleResolver(TargetGraph.EMPTY, transformer);
  public final SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
  public final SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

  public final Compiler compiler = CxxPlatformUtils.DEFAULT_PLATFORM.getCxx().resolve(ruleResolver);

  public BuildTarget newTarget(String fullyQualifiedName) {
    return BuildTargetFactory.newInstance(fullyQualifiedName);
  }

  public BuildRuleParams newParams(BuildTarget target) {
    return new FakeBuildRuleParamsBuilder(target).build();
  }

  /** Note: creates the {@link CxxPrecompiledHeaderTemplate}, add to ruleResolver index. */
  public CxxPrecompiledHeaderTemplate newPCH(
      BuildTarget target, SourcePath headerSourcePath, ImmutableSortedSet<BuildRule> deps) {
    return new CxxPrecompiledHeaderTemplate(
        newParams(target).copyAppendingExtraDeps(deps), ruleResolver, headerSourcePath);
  }

  public CxxSource.Builder newCxxSourceBuilder() {
    return CxxSource.builder().setType(CxxSource.Type.C);
  }

  public CxxSource newSource(String filename) {
    return newCxxSourceBuilder().setPath(new FakeSourcePath(filename)).build();
  }

  public CxxSource newSource() {
    return newSource("foo.cpp");
  }

  public CxxSourceRuleFactory.Builder newFactoryBuilder(BuildRuleParams params) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    return CxxSourceRuleFactory.builder()
        .setParams(params)
        .setResolver(ruleResolver)
        .setRuleFinder(ruleFinder)
        .setPathResolver(new SourcePathResolver(ruleFinder))
        .setCxxPlatform(PLATFORM_SUPPORTING_PCH)
        .setPicType(AbstractCxxSourceRuleFactory.PicType.PIC)
        .setCxxBuckConfig(CXX_CONFIG_PCH_ENABLED);
  }

  public CxxSourceRuleFactory.Builder newFactoryBuilder(BuildRuleParams params, String flag) {
    return newFactoryBuilder(params)
        .setCxxPreprocessorInput(
            ImmutableList.of(
                CxxPreprocessorInput.builder()
                    .setPreprocessorFlags(ImmutableMultimap.of(CxxSource.Type.C, flag))
                    .build()));
  }

  private CxxPrecompiledHeaderTemplate newPCH(BuildTarget pchTarget) {
    return newPCH(pchTarget, new FakeSourcePath("header.h"), /* deps */ ImmutableSortedSet.of());
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

  private boolean platformOkForPCHTests() {
    return Platform.detect() != Platform.WINDOWS;
  }

  /** @return exit code from that process */
  private int runBuiltBinary(String binaryTarget) throws Exception {
    return workspace
        .runCommand(
            workspace
                .resolve(
                    BuildTargets.getGenPath(
                        filesystem, workspace.newBuildTarget(binaryTarget), "%s"))
                .toString())
        .getExitCode();
  }

  /** Stolen from {@link PrecompiledHeaderIntegrationTest} */
  private static Matcher<BuckBuildLog> reportedTargetSuccessType(
      final BuildTarget target, final BuildRuleSuccessType successType) {
    return new CustomTypeSafeMatcher<BuckBuildLog>(
        "target: " + target.toString() + " with result: " + successType) {

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
  public void samePchIffSameFlags() throws Exception {
    assumeTrue(platformOkForPCHTests());

    BuildTarget pchTarget = newTarget("//test:pch");
    CxxPrecompiledHeaderTemplate pch = newPCH(pchTarget);
    ruleResolver.addToIndex(pch);

    BuildTarget lib1Target = newTarget("//test:lib1");
    BuildRuleParams lib1Params = newParams(lib1Target);
    CxxSourceRuleFactory factory1 =
        newFactoryBuilder(lib1Params, "-frtti")
            .setPrecompiledHeader(new DefaultBuildTargetSourcePath(pchTarget))
            .build();
    CxxPreprocessAndCompile lib1 =
        factory1.createPreprocessAndCompileBuildRule("lib1.cpp", newSource("lib1.cpp"));
    ruleResolver.addToIndex(lib1);
    ImmutableList<String> cmd1 =
        lib1.makeMainStep(pathResolver, Paths.get("/tmp/x"), false).getCommand();

    BuildTarget lib2Target = newTarget("//test:lib2");
    BuildRuleParams lib2Params = newParams(lib2Target);
    CxxSourceRuleFactory factory2 =
        newFactoryBuilder(lib2Params, "-frtti")
            .setPrecompiledHeader(new DefaultBuildTargetSourcePath(pchTarget))
            .build();
    CxxPreprocessAndCompile lib2 =
        factory2.createPreprocessAndCompileBuildRule("lib2.cpp", newSource("lib2.cpp"));
    ruleResolver.addToIndex(lib2);
    ImmutableList<String> cmd2 =
        lib2.makeMainStep(pathResolver, Paths.get("/tmp/x"), false).getCommand();

    BuildTarget lib3Target = newTarget("//test:lib3");
    BuildRuleParams lib3Params = newParams(lib3Target);
    CxxSourceRuleFactory factory3 =
        newFactoryBuilder(lib3Params, "-fno-rtti")
            .setPrecompiledHeader(new DefaultBuildTargetSourcePath(pchTarget))
            .build();
    CxxPreprocessAndCompile lib3 =
        factory3.createPreprocessAndCompileBuildRule("lib3.cpp", newSource("lib3.cpp"));
    ruleResolver.addToIndex(lib3);
    ImmutableList<String> cmd3 =
        lib3.makeMainStep(pathResolver, Paths.get("/tmp/x"), false).getCommand();

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
  public void userRuleChangesDependencyPCHRuleFlags() throws Exception {
    assumeTrue(platformOkForPCHTests());

    BuildTarget pchTarget = newTarget("//test:pch");
    CxxPrecompiledHeaderTemplate pch = newPCH(pchTarget);
    ruleResolver.addToIndex(pch);

    BuildTarget libTarget = newTarget("//test:lib");
    BuildRuleParams libParams = newParams(libTarget);
    CxxSourceRuleFactory factory1 =
        newFactoryBuilder(libParams, "-flag-for-factory")
            .setPrecompiledHeader(new DefaultBuildTargetSourcePath(pchTarget))
            .build();
    CxxPreprocessAndCompile lib =
        factory1.createPreprocessAndCompileBuildRule(
            "lib.cpp",
            newCxxSourceBuilder()
                .setPath(new FakeSourcePath("lib.cpp"))
                .setFlags(ImmutableList.of("-flag-for-source"))
                .build());
    ruleResolver.addToIndex(lib);
    ImmutableList<String> libCmd =
        lib.makeMainStep(pathResolver, Paths.get("/tmp/x"), false).getCommand();
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
        pchInstance.makeMainStep(pathResolver, Paths.get("/tmp/x")).getCommand();
    assertTrue(seek(pchCmd, "-flag-for-source").size() > 0);
    assertTrue(seek(pchCmd, "-flag-for-factory").size() > 0);
  }

  private static <T> void assertContains(ImmutableList<T> container, Iterable<T> items) {
    for (T item : items) {
      assertThat(container, Matchers.hasItem(item));
    }
  }

  @Test
  public void userRuleIncludePathsChangedByPCH() throws Exception {
    assumeTrue(platformOkForPCHTests());

    CxxPreprocessorInput cxxPreprocessorInput =
        CxxPreprocessorInput.builder()
            .addIncludes(
                CxxHeadersDir.of(
                    CxxPreprocessables.IncludeType.SYSTEM, new FakeSourcePath("/tmp/sys")))
            .build();

    BuildTarget lib1Target = newTarget("//some/other/dir:lib1");
    BuildRuleParams lib1Params = newParams(lib1Target);
    CxxSourceRuleFactory lib1Factory =
        newFactoryBuilder(lib1Params).addCxxPreprocessorInput(cxxPreprocessorInput).build();
    CxxPreprocessAndCompile lib1 =
        lib1Factory.createPreprocessAndCompileBuildRule("lib1.cpp", newSource("lib1.cpp"));
    ruleResolver.addToIndex(lib1);

    ImmutableList<String> lib1Cmd =
        lib1.makeMainStep(pathResolver, Paths.get("/tmp/x"), false).getCommand();

    BuildTarget pchTarget = newTarget("//test:pch");
    CxxPrecompiledHeaderTemplate pch =
        newPCH(pchTarget, new FakeSourcePath("header.h"), ImmutableSortedSet.of(lib1));
    ruleResolver.addToIndex(pch);

    BuildTarget lib2Target = newTarget("//test:lib2");
    BuildRuleParams lib2Params = newParams(lib2Target);
    CxxSourceRuleFactory lib2Factory =
        newFactoryBuilder(lib2Params)
            .setPrecompiledHeader(new DefaultBuildTargetSourcePath(pchTarget))
            .build();
    CxxPreprocessAndCompile lib2 =
        lib2Factory.createPreprocessAndCompileBuildRule("lib2.cpp", newSource("lib2.cpp"));
    ruleResolver.addToIndex(lib2);
    ImmutableList<String> lib2Cmd =
        lib2.makeMainStep(pathResolver, Paths.get("/tmp/y"), false).getCommand();

    CxxPrecompiledHeader pchInstance = null;
    for (BuildRule dep : lib2.getBuildDeps()) {
      if (dep instanceof CxxPrecompiledHeader) {
        pchInstance = (CxxPrecompiledHeader) dep;
      }
    }
    assertNotNull(pchInstance);
    ImmutableList<String> pchCmd =
        pchInstance.makeMainStep(pathResolver, Paths.get("/tmp/z")).getCommand();

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
    assumeTrue(platformOkForPCHTests());
    workspace.runBuckBuild("//basic_tests:main").assertSuccess();
  }

  @Test
  public void successfulBuildWithPchHavingDeps() throws Exception {
    assumeTrue(platformOkForPCHTests());
    workspace.runBuckBuild("//deps_test:bin").assertSuccess();
  }

  @Test
  public void changingPrecompilableHeaderCausesRecompile() throws Exception {
    assumeTrue(platformOkForPCHTests());

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
    assumeTrue(platformOkForPCHTests());

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
                        BuildTargets.getGenPath(
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
                        BuildTargets.getGenPath(
                            filesystem,
                            workspace.newBuildTarget(
                                "//recompile_after_include_changed:main#default"),
                            "%s"))
                    .toString())
            .getExitCode(),
        51);
  }

  private static void getAllFiles(TreeMap<Path, byte[]> out, Path dir) throws Exception {
    assertTrue(dir.toFile().isDirectory());
    for (Path relativeEntry : Files.list(dir).collect(Collectors.toList())) {
      if (relativeEntry.toFile().isDirectory()) {
        getAllFiles(out, relativeEntry);
      } else {
        out.put(relativeEntry, Files.readAllBytes(relativeEntry));
      }
    }
  }

  @Test
  public void deterministicHashesForSharedPCHs() throws Exception {
    assumeTrue(platformOkForPCHTests());

    Sha1HashCode pchHashA = null;
    workspace.runBuckBuild("//determinism/a:main").assertSuccess();
    BuckBuildLog buildLogA = workspace.getBuildLog();
    for (BuildTarget target : buildLogA.getAllTargets()) {
      if (target.toString().startsWith("//determinism/lib:pch#default,pch-cxx-")) {
        pchHashA = buildLogA.getLogEntry(target).getRuleKey();
        System.err.println("A: " + pchHashA);
      }
    }
    assertNotNull(pchHashA);

    Sha1HashCode pchHashB = null;
    workspace.runBuckBuild("//determinism/b:main").assertSuccess();
    BuckBuildLog buildLogB = workspace.getBuildLog();
    for (BuildTarget target : buildLogB.getAllTargets()) {
      if (target.toString().startsWith("//determinism/lib:pch#default,pch-cxx-")) {
        pchHashB = buildLogB.getLogEntry(target).getRuleKey();
        System.err.println("B: " + pchHashB);
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
        System.err.println("C: " + pchHashC);
      }
    }
    assertNotNull(pchHashC);
    assertNotEquals(pchHashA, pchHashC);
  }
}
