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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;


/**
 * High level tests for Precompiled header feature.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class PrecompiledHeaderFeatureTest {

  /**
   * Tests that PCH is only used when compiling in in-process combined preprocessing/compiling.
   *
   * It makes no sense to enable PCH otherwise (and may not even be possible for clang).
   */
  @RunWith(Theories.class)
  public static class OnlyUsePchInCombinedMode {

    @DataPoints
    public static final CxxPreprocessMode[] MODES = {
        CxxPreprocessMode.COMBINED,
        CxxPreprocessMode.SEPARATE,
    };

    @Theory
    public void test(CxxPreprocessMode mode) {
      CxxPreprocessAndCompile rule = preconfiguredSourceRuleFactoryBuilder()
          .build()
          .createPreprocessAndCompileBuildRule(
              "foo.c",
              preconfiguredCxxSourceBuilder().build(),
              mode);

      boolean usesPch = commandLineContainsPchFlag(rule);
      if (mode == CxxPreprocessMode.COMBINED) {
        assertTrue("should only use PCH in combined mode", usesPch);
      } else {
        assertFalse("should only use PCH in combined mode", usesPch);
      }
    }
  }

  /**
   * Tests that PCH is only used when a preprocessor is declared to use PCH.
   */
  @RunWith(Parameterized.class)
  public static class OnlyUsePchIfToolchainIsSupported {
    @Parameterized.Parameters(name = "{1}")
    public static Collection<Object[]> data() {
      CxxPlatform platformNotSupportingPch =
          DEFAULT_PLATFORM.withCpp(
              new PreprocessorProvider(
                  Paths.get("foopp"),
                  Optional.of(CxxToolProvider.Type.GCC)));
      CxxPlatform platformSupportingPch = PLATFORM_SUPPORTING_PCH;
      return Arrays.asList(new Object[][] {
          {platformNotSupportingPch, false},
          {platformSupportingPch, true},
      });
    }


    @Parameterized.Parameter(0)
    public CxxPlatform platform;

    @Parameterized.Parameter(1)
    public boolean supportsPch;

    @Test
    public void test() {
      CxxPreprocessAndCompile rule = preconfiguredSourceRuleFactoryBuilder()
          .setCxxPlatform(platform)
          .build()
          .createPreprocessAndCompileBuildRule(
              "foo.c",
              preconfiguredCxxSourceBuilder().build(),
              CxxPreprocessMode.COMBINED);
      boolean usesPch = commandLineContainsPchFlag(rule);
      if (supportsPch) {
        assertTrue("should only use PCH if toolchain supports it", usesPch);
      } else {
        assertFalse("should only use PCH if toolchain supports it", usesPch);
      }
    }
  }

  public static class PrecompiledHeaderBuildTargetGeneration {
    @Test
    public void buildTargetShouldDeriveFromSanitizedFlags() {
      class TestData {
        public CxxPrecompiledHeader generate(Path from) {
          CxxSourceRuleFactory factory = preconfiguredSourceRuleFactoryBuilder()
              .setCxxPlatform(PLATFORM_SUPPORTING_PCH.withDebugPathSanitizer(
                  new DebugPathSanitizer(
                      250,
                      File.separatorChar,
                      Paths.get("."),
                      ImmutableBiMap.of(from, Paths.get("melon")))))
              .build();
          BuildRule rule = factory.createPreprocessAndCompileBuildRule(
              "foo.c",
              preconfiguredCxxSourceBuilder()
                  .addFlags("-I", from.toString())
                  .build(),
              CxxPreprocessMode.COMBINED);
          return
              FluentIterable.from(rule.getDeps()).filter(CxxPrecompiledHeader.class).first().get();
        }
      }
      TestData testData = new TestData();

      Path root = Preconditions.checkNotNull(
          Iterables.getFirst(
              FileSystems.getDefault().getRootDirectories(),
              Paths.get(File.separator)));
      CxxPrecompiledHeader firstRule = testData.generate(root.resolve("first"));
      CxxPrecompiledHeader secondRule = testData.generate(root.resolve("second"));
      assertEquals(
          "Build target flavor generator should normalize away the absolute paths",
          firstRule.getBuildTarget(),
          secondRule.getBuildTarget());
    }

    @Test
    public void buildTargetShouldVaryWithCompilerFlags() {
      class TestData {
        public CxxPrecompiledHeader generate(Iterable<String> flags) {
          CxxSourceRuleFactory factory = preconfiguredSourceRuleFactoryBuilder()
              .putAllCompilerFlags(CxxSource.Type.C_CPP_OUTPUT, flags)
              .build();
          BuildRule rule = factory.createPreprocessAndCompileBuildRule(
              "foo.c",
              preconfiguredCxxSourceBuilder().build(),
              CxxPreprocessMode.COMBINED);
          return
              FluentIterable.from(rule.getDeps()).filter(CxxPrecompiledHeader.class).first().get();
        }
      }
      TestData testData = new TestData();

      CxxPrecompiledHeader firstRule =
          testData.generate(ImmutableList.of("-target=x86_64-apple-darwin-macho"));
      CxxPrecompiledHeader secondRule =
          testData.generate(ImmutableList.of("-target=armv7-apple-watchos-macho"));
      assertNotEquals(
          "Build target flavor generator should account for compiler flags",
          firstRule.getBuildTarget(),
          secondRule.getBuildTarget());
    }

    @Test
    public void buildTargetShouldVaryWithPreprocessorFlags() {
      class TestData {
        public CxxPrecompiledHeader generate(String flags) {
          CxxSourceRuleFactory factory = preconfiguredSourceRuleFactoryBuilder()
              .setCxxPreprocessorInput(
                  ImmutableList.of(
                      CxxPreprocessorInput.builder()
                          .setPreprocessorFlags(ImmutableMultimap.of(CxxSource.Type.C, flags))
                          .build()))
              .build();
          BuildRule rule = factory.createPreprocessAndCompileBuildRule(
              "foo.c",
              preconfiguredCxxSourceBuilder().build(),
              CxxPreprocessMode.COMBINED);
          return
              FluentIterable.from(rule.getDeps()).filter(CxxPrecompiledHeader.class).first().get();
        }
      }
      TestData testData = new TestData();

      CxxPrecompiledHeader firstRule = testData.generate("-DNDEBUG");
      CxxPrecompiledHeader secondRule = testData.generate("-UNDEBUG");
      assertNotEquals(
          "Build target flavor generator should account for preprocessor flags",
          firstRule.getBuildTarget(),
          secondRule.getBuildTarget());
    }

  }

  // Helpers and defaults

  /**
   * Checks if the command line generated for the build rule contains the -include-pch directive.
   *
   * This serves as an indicator that the file is being compiled with PCH enabled.
   */
  private static boolean commandLineContainsPchFlag(CxxPreprocessAndCompile rule) {
    return Iterables.tryFind(
        rule.makeMainStep(Paths.get("/tmp/unused_scratch_dir"), false).getCommand(),
        Predicates.equalTo("-include-pch")).isPresent();
  }

  /**
   * Configures a CxxSourceRuleFactory.Builder with some sane defaults for PCH tests.
   */
  private static CxxSourceRuleFactory.Builder preconfiguredSourceRuleFactoryBuilder() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    return CxxSourceRuleFactory.builder()
        .setParams(params)
        .setResolver(resolver)
        .setPathResolver(new SourcePathResolver(resolver))
        .setCxxPlatform(PLATFORM_SUPPORTING_PCH)
        .setPrefixHeader(new FakeSourcePath(("foo.pch")))
        .setPicType(AbstractCxxSourceRuleFactory.PicType.PDC)
        .setCxxBuckConfig(new CxxBuckConfig(FakeBuckConfig.builder().build()));
  }

  /**
   * Configures a CxxSource.Builder representing a C source file.
   */
  private static CxxSource.Builder preconfiguredCxxSourceBuilder() {
    return CxxSource.builder().setType(CxxSource.Type.C).setPath(new FakeSourcePath("foo.c"));
  }

  /**
   * The default cxx platform.
   *
   * Note that this may not support PCH depending on the system the test is running on.
   */
  private static final CxxPlatform DEFAULT_PLATFORM = DefaultCxxPlatforms.build(
      new CxxBuckConfig(FakeBuckConfig.builder().build()));

  private static final CxxPlatform PLATFORM_SUPPORTING_PCH =
      DEFAULT_PLATFORM.withCpp(
          new PreprocessorProvider(
              Paths.get("foopp"),
              Optional.of(CxxToolProvider.Type.CLANG)));
}
