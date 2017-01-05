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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

@SuppressWarnings("all")
public class CxxPrecompiledHeaderRuleTest {

  private static final CxxBuckConfig CXX_CONFIG_PCH_ENABLED =
      new CxxBuckConfig(
          FakeBuckConfig.builder()
          .setSections("[cxx]", "pch_enabled=true")
          .build());

  private static final PreprocessorProvider PREPROCESSOR_SUPPORTING_PCH =
      new PreprocessorProvider(
          Paths.get("foopp"),
          Optional.of(CxxToolProvider.Type.CLANG));

  private static final CxxPlatform PLATFORM_SUPPORTING_PCH =
      CxxPlatformUtils
          .build(CXX_CONFIG_PCH_ENABLED)
          .withCpp(PREPROCESSOR_SUPPORTING_PCH);

  public final TargetNodeToBuildRuleTransformer transformer =
      new DefaultTargetNodeToBuildRuleTransformer();

  public final BuildRuleResolver ruleResolver =
      new BuildRuleResolver(TargetGraph.EMPTY, transformer);
  public final SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
  public final SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

  public final Compiler compiler =
      CxxPlatformUtils.DEFAULT_PLATFORM.getCxx().resolve(ruleResolver);

  public BuildTarget newTarget(String fullyQualifiedName) {
    return BuildTargetFactory.newInstance(fullyQualifiedName);
  }

  public BuildRuleParams newParams(BuildTarget target) {
    return new FakeBuildRuleParamsBuilder(target).build();
  }

  /**
   * Note: creates the {@link CxxPrecompiledHeaderTemplate}, add to ruleResolver index.
   */
  public CxxPrecompiledHeaderTemplate newPCH(
      BuildTarget target,
      SourcePath headerSourcePath,
      ImmutableSortedSet<BuildRule> deps) {
    return new CxxPrecompiledHeaderTemplate(
        newParams(target).appendExtraDeps(deps),
        ruleResolver,
        pathResolver,
        headerSourcePath);
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

  public CxxSourceRuleFactory.Builder newFactoryBuilder(
      BuildTarget target,
      BuildRuleParams params) {
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

  public CxxSourceRuleFactory.Builder newFactoryBuilder(
      BuildTarget target,
      BuildRuleParams params,
      String flag) {
    return newFactoryBuilder(target, params)
        .setCxxPreprocessorInput(
            ImmutableList.of(
                CxxPreprocessorInput.builder()
                    .setPreprocessorFlags(
                        ImmutableMultimap.of(CxxSource.Type.C, flag))
                    .build()));
  }

  private CxxPrecompiledHeaderTemplate newPCH(BuildTarget pchTarget) {
    return newPCH(
        pchTarget,
        new FakeSourcePath("header.h"),
        /* deps */ ImmutableSortedSet.of());
  }

  /**
   * Return the sublist, starting at {@code toFind}, or empty list if not found.
   */
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

  @Test
  public void samePchIffSameFlags() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);

    BuildTarget pchTarget = newTarget("//test:pch");
    CxxPrecompiledHeaderTemplate pch = newPCH(pchTarget);
    ruleResolver.addToIndex(pch);

    BuildTarget lib1Target = newTarget("//test:lib1");
    BuildRuleParams lib1Params = newParams(lib1Target);
    CxxSourceRuleFactory factory1 = newFactoryBuilder(lib1Target, lib1Params, "-frtti")
        .setPrecompiledHeader(new BuildTargetSourcePath(pchTarget))
        .build();
    CxxPreprocessAndCompile lib1 = factory1.createPreprocessAndCompileBuildRule(
        "lib1.cpp",
        newSource("lib1.cpp"));
    ruleResolver.addToIndex(lib1);
    ImmutableList<String> cmd1 = lib1.makeMainStep(pathResolver, Paths.get("/tmp/x"), false).getCommand();

    BuildTarget lib2Target = newTarget("//test:lib2");
    BuildRuleParams lib2Params = newParams(lib2Target);
    CxxSourceRuleFactory factory2 = newFactoryBuilder(lib2Target, lib2Params, "-frtti")
        .setPrecompiledHeader(new BuildTargetSourcePath(pchTarget))
        .build();
    CxxPreprocessAndCompile lib2 = factory2.createPreprocessAndCompileBuildRule(
        "lib2.cpp",
        newSource("lib2.cpp"));
    ruleResolver.addToIndex(lib2);
    ImmutableList<String> cmd2 = lib2.makeMainStep(pathResolver, Paths.get("/tmp/x"), false).getCommand();

    BuildTarget lib3Target = newTarget("//test:lib3");
    BuildRuleParams lib3Params = newParams(lib3Target);
    CxxSourceRuleFactory factory3 = newFactoryBuilder(lib3Target, lib3Params, "-fno-rtti")
        .setPrecompiledHeader(new BuildTargetSourcePath(pchTarget))
        .build();
    CxxPreprocessAndCompile lib3 = factory3.createPreprocessAndCompileBuildRule(
        "lib3.cpp",
        newSource("lib3.cpp"));
    ruleResolver.addToIndex(lib3);
    ImmutableList<String> cmd3 = lib3.makeMainStep(pathResolver, Paths.get("/tmp/x"), false).getCommand();

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
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);

    BuildTarget pchTarget = newTarget("//test:pch");
    CxxPrecompiledHeaderTemplate pch = newPCH(pchTarget);
    ruleResolver.addToIndex(pch);

    BuildTarget libTarget = newTarget("//test:lib");
    BuildRuleParams libParams = newParams(libTarget);
    CxxSourceRuleFactory factory1 = newFactoryBuilder(libTarget, libParams, "-flag-for-factory")
        .setPrecompiledHeader(new BuildTargetSourcePath(pchTarget))
        .build();
    CxxPreprocessAndCompile lib = factory1.createPreprocessAndCompileBuildRule(
        "lib.cpp",
        newCxxSourceBuilder()
            .setPath(new FakeSourcePath("lib.cpp"))
            .setFlags(ImmutableList.of("-flag-for-source"))
            .build());
    ruleResolver.addToIndex(lib);
    ImmutableList<String> libCmd = lib.makeMainStep(pathResolver, Paths.get("/tmp/x"), false).getCommand();
    assertTrue(seek(libCmd, "-flag-for-source").size() > 0);
    assertTrue(seek(libCmd, "-flag-for-factory").size() > 0);

    CxxPrecompiledHeader pchInstance = null;
    for (BuildRule dep : lib.getDeps()) {
      if (dep instanceof CxxPrecompiledHeader) {
        pchInstance = (CxxPrecompiledHeader) dep;
      }
    }
    assertTrue(pchInstance != null);
    ImmutableList<String> pchCmd = pchInstance.makeMainStep(pathResolver, Paths.get("/tmp/x")).getCommand();
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
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);

    CxxPreprocessorInput cxxPreprocessorInput =
        CxxPreprocessorInput.builder()
            .addSystemIncludeRoots(Paths.get("/tmp/sys"))
            .build();

    BuildTarget lib1Target = newTarget("//some/other/dir:lib1");
    BuildRuleParams lib1Params = newParams(lib1Target);
    CxxSourceRuleFactory lib1Factory = newFactoryBuilder(lib1Target, lib1Params)
        .addCxxPreprocessorInput(cxxPreprocessorInput)
        .build();
    CxxPreprocessAndCompile lib1 = lib1Factory.createPreprocessAndCompileBuildRule(
        "lib1.cpp",
        newSource("lib1.cpp"));
    ruleResolver.addToIndex(lib1);

    ImmutableList<String> lib1Cmd = lib1.makeMainStep(pathResolver, Paths.get("/tmp/x"), false).getCommand();

    BuildTarget pchTarget = newTarget("//test:pch");
    CxxPrecompiledHeaderTemplate pch =
        newPCH(
            pchTarget,
            new FakeSourcePath("header.h"),
            ImmutableSortedSet.of(lib1));
    ruleResolver.addToIndex(pch);

    BuildTarget lib2Target = newTarget("//test:lib2");
    BuildRuleParams lib2Params = newParams(lib2Target);
    CxxSourceRuleFactory lib2Factory = newFactoryBuilder(lib2Target, lib2Params)
        .setPrecompiledHeader(new BuildTargetSourcePath(pchTarget))
        .build();
    CxxPreprocessAndCompile lib2 = lib2Factory.createPreprocessAndCompileBuildRule(
        "lib2.cpp",
        newSource("lib2.cpp"));
    ruleResolver.addToIndex(lib2);
    ImmutableList<String> lib2Cmd = lib2.makeMainStep(pathResolver, Paths.get("/tmp/y"), false).getCommand();

    CxxPrecompiledHeader pchInstance = null;
    for (BuildRule dep : lib2.getDeps()) {
      if (dep instanceof CxxPrecompiledHeader) {
        pchInstance = (CxxPrecompiledHeader) dep;
      }
    }
    assertTrue(pchInstance != null);
    ImmutableList<String> pchCmd = pchInstance.makeMainStep(pathResolver, Paths.get("/tmp/z")).getCommand();

    // (pretend that) lib1 has a dep resulting in adding this dir to the include path flags
    assertContains(lib1Cmd, ImmutableList.of("-isystem", "/tmp/sys"));

    // PCH should inherit those flags
    assertContains(pchCmd, ImmutableList.of("-isystem", "/tmp/sys"));

    // and because PCH uses them, these should be used in lib2 which uses PCH; also, used *first*
    assertContains(lib2Cmd, ImmutableList.of("-isystem", "/tmp/sys"));
    Iterator iter = lib2Cmd.iterator();
    while (iter.hasNext() && !iter.next().equals("-isystem")) {}
    assertEquals("/tmp/sys", iter.next());
  }

}
