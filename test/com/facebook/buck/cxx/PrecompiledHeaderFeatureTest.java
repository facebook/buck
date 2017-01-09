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
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;


/**
 * High level tests for Precompiled header feature.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class PrecompiledHeaderFeatureTest {

  /**
   * Tests that PCH is only used when a preprocessor is declared to use PCH.
   */
  @RunWith(Parameterized.class)
  public static class OnlyUsePchIfToolchainIsSupported {
    @Parameterized.Parameters(name = "{1}")
    public static Collection<Object[]> data() {
      CxxPlatform platformNotSupportingPch =
          PLATFORM_NOT_SUPPORTING_PCH.withCpp(
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
      BuildRuleResolver resolver =
          new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
      CxxPreprocessAndCompile rule = preconfiguredSourceRuleFactoryBuilder(resolver)
          .setCxxPlatform(platform)
          .setPrefixHeader(new FakeSourcePath(("foo.pch")))
          .build()
          .createPreprocessAndCompileBuildRule(
              "foo.c",
              preconfiguredCxxSourceBuilder().build());
      boolean usesPch = commandLineContainsPchFlag(
          new SourcePathResolver(new SourcePathRuleFinder(resolver)), rule);
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
          BuildRuleResolver resolver = new BuildRuleResolver(
              TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
          CxxSourceRuleFactory factory = preconfiguredSourceRuleFactoryBuilder(resolver)
              .setCxxPlatform(
                  PLATFORM_SUPPORTING_PCH.withCompilerDebugPathSanitizer(
                      new MungingDebugPathSanitizer(
                          250,
                          File.separatorChar,
                          Paths.get("."),
                          ImmutableBiMap.of(from, Paths.get("melon")))))
              .setPrefixHeader(new FakeSourcePath(("foo.pch")))
              .build();
          BuildRule rule = factory.createPreprocessAndCompileBuildRule(
              "foo.c",
              preconfiguredCxxSourceBuilder()
                  .addFlags("-I", from.toString())
                  .build());
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
          BuildRuleResolver resolver = new BuildRuleResolver(
              TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
          CxxSourceRuleFactory factory = preconfiguredSourceRuleFactoryBuilder(resolver)
              .putAllCompilerFlags(CxxSource.Type.C_CPP_OUTPUT, flags)
              .setPrefixHeader(new FakeSourcePath(("foo.pch")))
              .build();
          BuildRule rule = factory.createPreprocessAndCompileBuildRule(
              "foo.c",
              preconfiguredCxxSourceBuilder().build());
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
          BuildRuleResolver resolver = new BuildRuleResolver(
              TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
          CxxSourceRuleFactory factory = preconfiguredSourceRuleFactoryBuilder(resolver)
              .setCxxPreprocessorInput(
                  ImmutableList.of(
                      CxxPreprocessorInput.builder()
                          .setPreprocessorFlags(ImmutableMultimap.of(CxxSource.Type.C, flags))
                          .build()))
              .setPrefixHeader(new FakeSourcePath(("foo.pch")))
              .build();
          BuildRule rule = factory.createPreprocessAndCompileBuildRule(
              "foo.c",
              preconfiguredCxxSourceBuilder().build());
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
  private static boolean commandLineContainsPchFlag(
      SourcePathResolver resolver, CxxPreprocessAndCompile rule) {
    return Iterables.tryFind(
        rule.makeMainStep(resolver, Paths.get("/tmp/unused_scratch_dir"), false).getCommand(),
        "-include-pch"::equals).isPresent();
  }

  /**
   * Configures a CxxSourceRuleFactory.Builder with some sane defaults for PCH tests.
   * Note: doesn't call "setPrefixHeader", which actually sets the PCH parameters; caller
   * needs to do that in their various tests.
   */
  private static CxxSourceRuleFactory.Builder preconfiguredSourceRuleFactoryBuilder(
      String targetPath, BuildRuleResolver ruleResolver) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance(targetPath);
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    return CxxSourceRuleFactory.builder()
        .setParams(params)
        .setResolver(ruleResolver)
        .setPathResolver(pathResolver)
        .setRuleFinder(ruleFinder)
        .setCxxPlatform(PLATFORM_SUPPORTING_PCH)
        .setPicType(AbstractCxxSourceRuleFactory.PicType.PDC)
        .setCxxBuckConfig(CXX_CONFIG_PCH_ENABLED);
  }

  private static CxxSourceRuleFactory.Builder preconfiguredSourceRuleFactoryBuilder(
      BuildRuleResolver resolver) {
    return preconfiguredSourceRuleFactoryBuilder("//foo:bar", resolver);
  }

  /**
   * Configures a CxxSource.Builder representing a C source file.
   */
  private static CxxSource.Builder preconfiguredCxxSourceBuilder() {
    return CxxSource.builder().setType(CxxSource.Type.C).setPath(new FakeSourcePath("foo.c"));
  }

  private static final CxxBuckConfig CXX_CONFIG_PCH_DISABLED =
      new CxxBuckConfig(
          FakeBuckConfig.builder()
          .setSections("[cxx]", "pch_enabled=false")
          .build());

  private static final CxxPlatform PLATFORM_NOT_SUPPORTING_PCH =
      CxxPlatformUtils.build(CXX_CONFIG_PCH_DISABLED);

  private static final CxxBuckConfig CXX_CONFIG_PCH_ENABLED =
      new CxxBuckConfig(
          FakeBuckConfig.builder()
          .setSections("[cxx]", "pch_enabled=true")
          .build());

  private static final CxxPlatform PLATFORM_SUPPORTING_PCH =
      CxxPlatformUtils
          .build(CXX_CONFIG_PCH_ENABLED)
          .withCpp(
              new PreprocessorProvider(
                  Paths.get("foopp"),
                  Optional.of(CxxToolProvider.Type.CLANG)));
}
