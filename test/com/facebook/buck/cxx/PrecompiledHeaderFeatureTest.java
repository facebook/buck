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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.config.Config;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultCellPathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
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
          .setPrefixHeader(new FakeSourcePath(("foo.pch")))
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
      CxxPreprocessAndCompile rule = preconfiguredSourceRuleFactoryBuilder()
          .setCxxPlatform(platform)
          .setPrefixHeader(new FakeSourcePath(("foo.pch")))
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
              .setPrefixHeader(new FakeSourcePath(("foo.pch")))
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
              .setPrefixHeader(new FakeSourcePath(("foo.pch")))
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

  public static class ExportedHeaderRuleCacheTests {
    @Test
    public void ensureSameObjIfSameFlags() {
      // Scenario: three rules:
      // foo, bar are "cxx_binary" rules which have "prefix_header" set to "//baz:bazheader".
      // baz is an "export_file" with srcs == out == "bazheader.h".
      //
      // The PCH generated for the "clients" foo and bar, instantiated from the header provided
      // by "baz", should be identical in both cases (maximizing object reuse to, among other
      // benefits, avoid rebuilding PCH's many times, like once per rule).

      final Config config = new Config();
      final ProjectFilesystem fs = new FakeProjectFilesystem();
      final CellPathResolver cellResolver = new DefaultCellPathResolver(fs.getRootPath(), config);
      final TargetGraph graph = TargetGraphFactory.newInstance();
      final BuildRuleResolver ruleResolver =
          new BuildRuleResolver(graph, new DefaultTargetNodeToBuildRuleTransformer());

      BuildTarget targetBaz = BuildTargetFactory.newInstance("//baz:bazheader");
      BuildRuleParams paramsBaz =
          new BuildRuleParams(
              targetBaz,
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
              fs,
              cellResolver);

      ExportFileDescription descriptionBaz = new ExportFileDescription();
      ExportFileDescription.Arg bazArgs = descriptionBaz.createUnpopulatedConstructorArg();
      SourcePath sourceBaz = new FakeSourcePath("bazheader.h");
      bazArgs.src = Optional.of(sourceBaz);
      bazArgs.out = Optional.<String>absent();
      bazArgs.mode = Optional.of(ExportFileDescription.Mode.REFERENCE);
      ruleResolver.addToIndex(
          new ExportFileDescription()
          .createBuildRule(graph, paramsBaz, ruleResolver, bazArgs));

      // first binary rule
      CxxSourceRuleFactory factoryFoo =
          preconfiguredSourceRuleFactoryBuilder("//foo:foo_binary", ruleResolver)
              .setPrefixHeader(new BuildTargetSourcePath(targetBaz))
              .build();
      CxxPreprocessAndCompile ruleFoo =
          factoryFoo.createPreprocessAndCompileBuildRule(
              "foo.cpp", preconfiguredCxxSourceBuilder().build(), CxxPreprocessMode.COMBINED);
      CxxPrecompiledHeader pch1 =
          FluentIterable.from(ruleFoo.getDeps()).filter(CxxPrecompiledHeader.class).first().get();

      // 2nd binary rule
      CxxSourceRuleFactory factoryBar =
          preconfiguredSourceRuleFactoryBuilder("//bar:bar_binary", ruleResolver)
              .setPrefixHeader(new BuildTargetSourcePath(targetBaz))
              .build();
      CxxPreprocessAndCompile ruleBar =
          factoryBar.createPreprocessAndCompileBuildRule(
              "bar.cpp", preconfiguredCxxSourceBuilder().build(), CxxPreprocessMode.COMBINED);
      CxxPrecompiledHeader pch2 =
          FluentIterable.from(ruleBar.getDeps()).filter(CxxPrecompiledHeader.class).first().get();

      assertSame(
          "PCH's with same flags (even used in different rules) should be the same object.",
          pch1, pch2);
    }

    @Test
    public void ensureDiffObjIfDiffFlags() {
      // Scenario: three rules:
      // foo, bar are "cxx_binary" rules which have "prefix_header" set to "//baz:bazheader".
      // baz is an "export_file" with srcs == out == "bazheader.h".
      //
      // The PCH generated for the "clients" foo and bar, instantiated from the header provided
      // by "baz", should be different in the two cases, due to the two binaries using a different
      // set of compile flags.

      final Config config = new Config();
      final ProjectFilesystem fs = new FakeProjectFilesystem();
      final CellPathResolver cellResolver = new DefaultCellPathResolver(fs.getRootPath(), config);
      final TargetGraph graph = TargetGraphFactory.newInstance();
      final BuildRuleResolver ruleResolver =
          new BuildRuleResolver(graph, new DefaultTargetNodeToBuildRuleTransformer());

      BuildTarget targetBaz = BuildTargetFactory.newInstance("//baz:bazheader");
      BuildRuleParams paramsBaz =
          new BuildRuleParams(
              targetBaz,
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
              fs,
              cellResolver);

      ExportFileDescription descriptionBaz = new ExportFileDescription();
      ExportFileDescription.Arg bazArgs = descriptionBaz.createUnpopulatedConstructorArg();
      SourcePath sourceBaz = new FakeSourcePath("bazheader.h");
      bazArgs.src = Optional.of(sourceBaz);
      bazArgs.out = Optional.<String>absent();
      bazArgs.mode = Optional.of(ExportFileDescription.Mode.REFERENCE);
      ruleResolver.addToIndex(
          new ExportFileDescription()
          .createBuildRule(graph, paramsBaz, ruleResolver, bazArgs));

      // first binary rule
      CxxSourceRuleFactory factoryFoo =
          preconfiguredSourceRuleFactoryBuilder("//foo:foo_binary", ruleResolver)
              .setCxxPreprocessorInput(
                  ImmutableList.of(
                      CxxPreprocessorInput.builder()
                          .setPreprocessorFlags(
                              ImmutableMultimap.of(CxxSource.Type.C, "-DNDEBUG"))
                          .build()))
              .setPrefixHeader(new BuildTargetSourcePath(targetBaz))
              .build();
      CxxPreprocessAndCompile ruleFoo =
          factoryFoo.createPreprocessAndCompileBuildRule(
              "foo.cpp", preconfiguredCxxSourceBuilder().build(), CxxPreprocessMode.COMBINED);
      CxxPrecompiledHeader pch1 =
          FluentIterable.from(ruleFoo.getDeps()).filter(CxxPrecompiledHeader.class).first().get();

      // 2nd binary rule
      CxxSourceRuleFactory factoryBar =
          preconfiguredSourceRuleFactoryBuilder("//bar:bar_binary", ruleResolver)
              .setCxxPreprocessorInput(
                  ImmutableList.of(
                      CxxPreprocessorInput.builder()
                          .setPreprocessorFlags(
                              ImmutableMultimap.of(CxxSource.Type.C, "-UNDEBUG"))
                          .build()))
              .setPrefixHeader(new BuildTargetSourcePath(targetBaz))
              .build();
      CxxPreprocessAndCompile ruleBar =
          factoryBar.createPreprocessAndCompileBuildRule(
              "bar.cpp", preconfiguredCxxSourceBuilder().build(), CxxPreprocessMode.COMBINED);
      CxxPrecompiledHeader pch2 =
          FluentIterable.from(ruleBar.getDeps()).filter(CxxPrecompiledHeader.class).first().get();

      assertNotSame(
          "PCH's with different flags should be distinct objects.",
          pch1, pch2);
      assertNotEquals(
          "PCH's with different flags should be distinct, un-equal objects.",
          pch1, pch2);
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
   * Note: doesn't call "setPrefixHeader", which actually sets the PCH parameters; caller
   * needs to do that in their various tests.
   */
  private static CxxSourceRuleFactory.Builder preconfiguredSourceRuleFactoryBuilder(
      String targetPath, BuildRuleResolver ruleResolver) {
    BuildTarget target = BuildTargetFactory.newInstance(targetPath);
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    return CxxSourceRuleFactory.builder()
        .setParams(params)
        .setResolver(ruleResolver)
        .setPathResolver(new SourcePathResolver(ruleResolver))
        .setCxxPlatform(PLATFORM_SUPPORTING_PCH)
        .setPicType(AbstractCxxSourceRuleFactory.PicType.PDC)
        .setCxxBuckConfig(CXX_CONFIG_PCH_ENABLED);
  }

  private static CxxSourceRuleFactory.Builder preconfiguredSourceRuleFactoryBuilder() {
    return preconfiguredSourceRuleFactoryBuilder(
        "//foo:bar",
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
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
      DefaultCxxPlatforms.build(CXX_CONFIG_PCH_DISABLED);

  private static final CxxBuckConfig CXX_CONFIG_PCH_ENABLED =
      new CxxBuckConfig(
          FakeBuckConfig.builder()
          .setSections("[cxx]", "pch_enabled=true")
          .build());

  private static final CxxPlatform PLATFORM_SUPPORTING_PCH =
      DefaultCxxPlatforms
          .build(CXX_CONFIG_PCH_ENABLED)
          .withCpp(
              new PreprocessorProvider(
                  Paths.get("foopp"),
                  Optional.of(CxxToolProvider.Type.CLANG)));
}
