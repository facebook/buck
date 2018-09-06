/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.DependencyAggregationTestUtil;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.toolprovider.impl.BinaryBuildRuleToolProvider;
import com.facebook.buck.cxx.toolchain.CompilerProvider;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxToolProvider;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.PreprocessorProvider;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.shell.ShBinary;
import com.facebook.buck.shell.ShBinaryBuilder;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class CxxSourceRuleFactoryTest {

  private static final ProjectFilesystem PROJECT_FILESYSTEM = new FakeProjectFilesystem();

  private static final CxxPlatform CXX_PLATFORM =
      CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

  private static <T> void assertContains(ImmutableList<T> container, Iterable<T> items) {
    for (T item : items) {
      assertThat(container, Matchers.hasItem(item));
    }
  }

  public static class CxxSourceRuleFactoryTests {
    private static FakeBuildRule createFakeBuildRule(String target, BuildRule... deps) {
      BuildTarget buildTarget = BuildTargetFactory.newInstance(target);
      return new FakeBuildRule(
          buildTarget,
          new FakeProjectFilesystem(),
          TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.copyOf(deps)));
    }

    @Test
    public void createPreprocessAndCompileBuildRulePropagatesCxxPreprocessorDeps() {
      BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

      FakeBuildRule dep = graphBuilder.addToIndex(new FakeBuildRule("//:dep1"));

      CxxPreprocessorInput cxxPreprocessorInput =
          CxxPreprocessorInput.builder().addRules(dep.getBuildTarget()).build();

      CxxSourceRuleFactory cxxSourceRuleFactory =
          CxxSourceRuleFactory.builder()
              .setProjectFilesystem(PROJECT_FILESYSTEM)
              .setBaseBuildTarget(target)
              .setActionGraphBuilder(graphBuilder)
              .setPathResolver(pathResolver)
              .setRuleFinder(ruleFinder)
              .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
              .setCxxPlatform(CXX_PLATFORM)
              .addCxxPreprocessorInput(cxxPreprocessorInput)
              .setPicType(PicType.PDC)
              .build();

      String name = "foo/bar.cpp";
      SourcePath input = FakeSourcePath.of(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
      CxxSource cxxSource = CxxSource.of(CxxSource.Type.CXX, input, ImmutableList.of());

      BuildRule cxxPreprocess =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(name, cxxSource);
      assertThat(
          DependencyAggregationTestUtil.getDisaggregatedDeps(cxxPreprocess)::iterator,
          contains((BuildRule) dep));
      cxxPreprocess = cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(name, cxxSource);
      assertThat(
          DependencyAggregationTestUtil.getDisaggregatedDeps(cxxPreprocess)::iterator,
          contains((BuildRule) dep));
    }

    @Test
    public void preprocessFlagsFromPlatformArePropagated() {
      BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

      ImmutableList<String> platformFlags = ImmutableList.of("-some", "-flags");
      CxxPlatform platform =
          CxxPlatformUtils.build(
              new CxxBuckConfig(
                  FakeBuckConfig.builder()
                      .setSections(
                          ImmutableMap.of(
                              "cxx",
                              ImmutableMap.of("cxxppflags", Joiner.on(" ").join(platformFlags))))
                      .build()));

      CxxPreprocessorInput cxxPreprocessorInput = CxxPreprocessorInput.of();

      CxxSourceRuleFactory cxxSourceRuleFactory =
          CxxSourceRuleFactory.builder()
              .setProjectFilesystem(PROJECT_FILESYSTEM)
              .setBaseBuildTarget(target)
              .setActionGraphBuilder(graphBuilder)
              .setPathResolver(pathResolver)
              .setRuleFinder(ruleFinder)
              .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
              .setCxxPlatform(platform)
              .addCxxPreprocessorInput(cxxPreprocessorInput)
              .setPicType(PicType.PDC)
              .build();

      String name = "source.cpp";
      CxxSource cxxSource =
          CxxSource.of(CxxSource.Type.CXX, FakeSourcePath.of(name), ImmutableList.of());

      // Verify that platform flags make it to the compile rule.
      CxxPreprocessAndCompile cxxPreprocess =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(name, cxxSource);
      assertNotEquals(
          -1,
          Collections.indexOfSubList(
              Arg.stringify(
                  cxxPreprocess
                      .getPreprocessorDelegate()
                      .get()
                      .getCommand(CxxToolFlags.of(), Optional.empty(), pathResolver),
                  pathResolver),
              platformFlags));
      CxxPreprocessAndCompile cxxPreprocessAndCompile =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(name, cxxSource);
      assertNotEquals(
          -1,
          Collections.indexOfSubList(
              Arg.stringify(
                  cxxPreprocessAndCompile
                      .getPreprocessorDelegate()
                      .get()
                      .getCommand(CxxToolFlags.of(), Optional.empty(), pathResolver),
                  pathResolver),
              platformFlags));
    }

    @Test
    public void createCompileBuildRulePropagatesBuildRuleSourcePathDeps() {
      BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

      FakeBuildRule dep = createFakeBuildRule("//:test");
      dep.setOutputFile("foo");
      graphBuilder.addToIndex(dep);
      SourcePath input = dep.getSourcePathToOutput();
      CxxSourceRuleFactory cxxSourceRuleFactory =
          CxxSourceRuleFactory.builder()
              .setProjectFilesystem(PROJECT_FILESYSTEM)
              .setBaseBuildTarget(target)
              .setActionGraphBuilder(graphBuilder)
              .setPathResolver(pathResolver)
              .setRuleFinder(ruleFinder)
              .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
              .setCxxPlatform(CXX_PLATFORM)
              .setPicType(PicType.PDC)
              .build();

      String nameCompile = "foo/bar.ii";
      CxxSource cxxSourceCompile =
          CxxSource.of(CxxSource.Type.CXX_CPP_OUTPUT, input, ImmutableList.of());
      CxxPreprocessAndCompile cxxCompile =
          cxxSourceRuleFactory.requireCompileBuildRule(nameCompile, cxxSourceCompile);
      assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxCompile.getBuildDeps());

      String namePreprocessAndCompile = "foo/bar.cpp";
      CxxSource cxxSourcePreprocessAndCompile =
          CxxSource.of(CxxSource.Type.CXX, input, ImmutableList.of());
      CxxPreprocessAndCompile cxxPreprocessAndCompile =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
              namePreprocessAndCompile, cxxSourcePreprocessAndCompile);
      assertThat(
          DependencyAggregationTestUtil.getDisaggregatedDeps(cxxPreprocessAndCompile)::iterator,
          contains((BuildRule) dep));
    }

    @Test
    public void createCompileBuildRulePicOption() {
      BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
      BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);

      CxxSourceRuleFactory.Builder cxxSourceRuleFactoryBuilder =
          CxxSourceRuleFactory.builder()
              .setProjectFilesystem(PROJECT_FILESYSTEM)
              .setBaseBuildTarget(target)
              .setActionGraphBuilder(graphBuilder)
              .setPathResolver(pathResolver)
              .setRuleFinder(ruleFinder)
              .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
              .setCxxPlatform(CXX_PLATFORM);
      CxxSourceRuleFactory cxxSourceRuleFactoryPDC =
          cxxSourceRuleFactoryBuilder.setPicType(PicType.PDC).build();
      CxxSourceRuleFactory cxxSourceRuleFactoryPIC =
          cxxSourceRuleFactoryBuilder.setPicType(PicType.PIC).build();

      String name = "foo/bar.ii";
      CxxSource cxxSource =
          CxxSource.of(CxxSource.Type.CXX_CPP_OUTPUT, FakeSourcePath.of(name), ImmutableList.of());

      // Verify building a non-PIC compile rule does *not* have the "-fPIC" flag and has the
      // expected compile target.
      CxxPreprocessAndCompile noPicCompile =
          cxxSourceRuleFactoryPDC.requireCompileBuildRule(name, cxxSource);
      assertFalse(noPicCompile.makeMainStep(context, false).getCommand().contains("-fPIC"));
      assertEquals(
          cxxSourceRuleFactoryPDC.createCompileBuildTarget(name), noPicCompile.getBuildTarget());

      // Verify building a PIC compile rule *does* have the "-fPIC" flag and has the
      // expected compile target.
      CxxPreprocessAndCompile picCompile =
          cxxSourceRuleFactoryPIC.requireCompileBuildRule(name, cxxSource);
      assertTrue(picCompile.makeMainStep(context, false).getCommand().contains("-fPIC"));
      assertEquals(
          cxxSourceRuleFactoryPIC.createCompileBuildTarget(name), picCompile.getBuildTarget());

      name = "foo/bar.cpp";
      cxxSource = CxxSource.of(CxxSource.Type.CXX, FakeSourcePath.of(name), ImmutableList.of());

      // Verify building a non-PIC compile rule does *not* have the "-fPIC" flag and has the
      // expected compile target.
      CxxPreprocessAndCompile noPicPreprocessAndCompile =
          cxxSourceRuleFactoryPDC.requirePreprocessAndCompileBuildRule(name, cxxSource);
      assertFalse(
          noPicPreprocessAndCompile.makeMainStep(context, false).getCommand().contains("-fPIC"));
      assertEquals(
          cxxSourceRuleFactoryPDC.createCompileBuildTarget(name),
          noPicPreprocessAndCompile.getBuildTarget());

      // Verify building a PIC compile rule *does* have the "-fPIC" flag and has the
      // expected compile target.
      CxxPreprocessAndCompile picPreprocessAndCompile =
          cxxSourceRuleFactoryPIC.requirePreprocessAndCompileBuildRule(name, cxxSource);
      assertTrue(
          picPreprocessAndCompile.makeMainStep(context, false).getCommand().contains("-fPIC"));
      assertEquals(
          cxxSourceRuleFactoryPIC.createCompileBuildTarget(name),
          picPreprocessAndCompile.getBuildTarget());
    }

    @Test
    public void checkPrefixHeaderIsIncluded() {
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
      BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);
      BuildTarget target = BuildTargetFactory.newInstance("//:target");
      ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

      BuckConfig buckConfig =
          FakeBuckConfig.builder()
              .setFilesystem(filesystem)
              .setSections(ImmutableMap.of("cxx", ImmutableMap.of("pch_enabled", "false")))
              .build();
      CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
      CxxPlatform platform = CxxPlatformUtils.build(cxxBuckConfig);

      String prefixHeaderName = "test.pch";
      SourcePath prefixHeaderSourcePath = FakeSourcePath.of(filesystem, prefixHeaderName);

      CxxSourceRuleFactory cxxSourceRuleFactory =
          CxxSourceRuleFactory.builder()
              .setProjectFilesystem(PROJECT_FILESYSTEM)
              .setBaseBuildTarget(target)
              .setActionGraphBuilder(graphBuilder)
              .setPathResolver(pathResolver)
              .setRuleFinder(ruleFinder)
              .setCxxBuckConfig(cxxBuckConfig)
              .setCxxPlatform(platform)
              .setPrefixHeader(prefixHeaderSourcePath)
              .setPicType(PicType.PDC)
              .build();

      String objcSourceName = "test.m";
      CxxSource objcSource =
          CxxSource.of(CxxSource.Type.OBJC, FakeSourcePath.of(objcSourceName), ImmutableList.of());
      CxxPreprocessAndCompile objcPreprocessAndCompile =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(objcSourceName, objcSource);

      ImmutableList<String> explicitPrefixHeaderRelatedFlags =
          ImmutableList.of("-include", filesystem.resolve(prefixHeaderName).toString());

      CxxPreprocessAndCompileStep step = objcPreprocessAndCompile.makeMainStep(context, false);
      assertContains(step.getCommand(), explicitPrefixHeaderRelatedFlags);
    }

    @Test
    public void duplicateRuleFetchedFromResolverShouldCreateTheSameTarget() {
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
      BuildTarget target = BuildTargetFactory.newInstance("//:target");
      ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

      BuckConfig buckConfig = FakeBuckConfig.builder().setFilesystem(filesystem).build();
      CxxPlatform platform = CxxPlatformUtils.build(new CxxBuckConfig(buckConfig));

      CxxSourceRuleFactory cxxSourceRuleFactory =
          CxxSourceRuleFactory.builder()
              .setProjectFilesystem(PROJECT_FILESYSTEM)
              .setBaseBuildTarget(target)
              .setActionGraphBuilder(graphBuilder)
              .setPathResolver(pathResolver)
              .setRuleFinder(ruleFinder)
              .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
              .setCxxPlatform(platform)
              .setPicType(PicType.PDC)
              .build();

      String objcSourceName = "test.m";
      CxxSource objcSource =
          CxxSource.of(CxxSource.Type.OBJC, FakeSourcePath.of(objcSourceName), ImmutableList.of());
      CxxPreprocessAndCompile objcCompile =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(objcSourceName, objcSource);

      // Make sure we can get the same build rule twice.
      CxxPreprocessAndCompile objcCompile2 =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(objcSourceName, objcSource);

      assertEquals(objcCompile.getBuildTarget(), objcCompile2.getBuildTarget());
    }

    @Test
    public void createPreprocessAndCompileBuildRulePropagatesToolDeps() {
      BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

      ShBinary cxxpp =
          new ShBinaryBuilder(BuildTargetFactory.newInstance("//:cxxpp"))
              .setMain(FakeSourcePath.of("blah"))
              .build(graphBuilder);
      ShBinary cxx =
          new ShBinaryBuilder(BuildTargetFactory.newInstance("//:cxx"))
              .setMain(FakeSourcePath.of("blah"))
              .build(graphBuilder);

      CxxPlatform cxxPlatform =
          CXX_PLATFORM
              .withCxxpp(
                  new PreprocessorProvider(
                      new BinaryBuildRuleToolProvider(cxxpp.getBuildTarget(), ""),
                      CxxToolProvider.Type.GCC))
              .withCxx(
                  new CompilerProvider(
                      new BinaryBuildRuleToolProvider(cxx.getBuildTarget(), ""),
                      CxxToolProvider.Type.GCC));

      CxxSourceRuleFactory cxxSourceRuleFactory =
          CxxSourceRuleFactory.builder()
              .setProjectFilesystem(PROJECT_FILESYSTEM)
              .setBaseBuildTarget(target)
              .setActionGraphBuilder(graphBuilder)
              .setPathResolver(pathResolver)
              .setRuleFinder(ruleFinder)
              .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
              .setCxxPlatform(cxxPlatform)
              .addCxxPreprocessorInput(CxxPreprocessorInput.of())
              .setPicType(PicType.PDC)
              .build();

      String name = "foo/bar.cpp";
      SourcePath input = FakeSourcePath.of(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
      CxxSource cxxSource = CxxSource.of(CxxSource.Type.CXX, input, ImmutableList.of());

      BuildRule cxxPreprocess =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(name, cxxSource);
      assertThat(cxxPreprocess.getBuildDeps(), hasItems(cxx, cxxpp));
      cxxPreprocess = cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(name, cxxSource);
      assertThat(cxxPreprocess.getBuildDeps(), hasItems(cxx, cxxpp));
    }
  }

  @RunWith(Parameterized.class)
  public static class RulesForDifferentSourcesShouldCreateSeaparateTargets {
    @SuppressWarnings("serial")
    Map<String, String[]> testExampleSourceSets =
        new HashMap<String, String[]>() {
          {
            put("Preprocessable type", new String[] {"1/2/3.c", "1_2/3.c", "1/2_3.c", "1_2_3.c"});
            put("Compilable type", new String[] {"1/2/3.i", "1_2/3.i", "1/2_3.i", "1_2_3.i"});
            put(
                "Same file in different directories",
                new String[] {"one-path/file1.c", "another-path/file1.c"});
            put(
                "Various ASCII chars",
                new String[] {
                  "special/chars.c",
                  "special_chars.c",
                  "special chars.c",
                  "special!chars.c",
                  "special(chars.c",
                  "special,chars.c"
                });
            put(
                "Non-ASCII chars",
                new String[] {
                  "special/chars.c", "specialאchars.c", "special漢chars.c", "specialДchars.c"
                });
            put("One-char names", new String[] {"_.c", ",.c", "漢.c"});
          }
        };

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object> data() {
      return Arrays.asList(
          new Object[] {
            "Preprocessable type",
            "Compilable type",
            "Same file in different directories",
            "Various ASCII chars",
            "Non-ASCII chars",
            "One-char names",
          });
    }

    @Parameterized.Parameter(0)
    public String testExampleSourceSet;

    @Test
    public void test() {
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
      BuildTarget target = BuildTargetFactory.newInstance("//:target");
      ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

      BuckConfig buckConfig = FakeBuckConfig.builder().setFilesystem(filesystem).build();
      CxxPlatform platform = CxxPlatformUtils.build(new CxxBuckConfig(buckConfig));

      CxxSourceRuleFactory cxxSourceRuleFactory =
          CxxSourceRuleFactory.builder()
              .setProjectFilesystem(PROJECT_FILESYSTEM)
              .setBaseBuildTarget(target)
              .setActionGraphBuilder(graphBuilder)
              .setPathResolver(pathResolver)
              .setRuleFinder(ruleFinder)
              .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
              .setCxxPlatform(platform)
              .setPicType(PicType.PDC)
              .build();

      String[] sourceNames = testExampleSourceSets.get(testExampleSourceSet);
      Map<String, CxxSource> sources = new HashMap<>();
      for (String sourceName : sourceNames) {
        sources.put(
            sourceName,
            CxxSource.of(CxxSource.Type.OBJC, FakeSourcePath.of(sourceName), ImmutableList.of()));
      }

      ImmutableMap<CxxPreprocessAndCompile, SourcePath> rules =
          cxxSourceRuleFactory.requirePreprocessAndCompileRules(ImmutableMap.copyOf(sources));

      assertEquals(
          String.format("Expected %d rules, but found only %d", sourceNames.length, rules.size()),
          sourceNames.length,
          rules.size());
    }
  }

  @RunWith(Parameterized.class)
  public static class CorrectFlagsAreUsedForCompileAndPreprocessBuildRules {

    private static final ImmutableList<String> asflags = ImmutableList.of("-asflag", "-asflag");
    private static final ImmutableList<String> cflags = ImmutableList.of("-cflag", "-cflag");
    private static final ImmutableList<String> cxxflags = ImmutableList.of("-cxxflag", "-cxxflag");
    private static final ImmutableList<String> asppflags =
        ImmutableList.of("-asppflag", "-asppflag");
    private static final ImmutableList<String> cppflags = ImmutableList.of("-cppflag", "-cppflag");
    private static final ImmutableList<String> cxxppflags =
        ImmutableList.of("-cxxppflag", "-cxxppflag");

    private static final ImmutableList<String> explicitCompilerFlags =
        ImmutableList.of("-explicit-compilerflag");
    private static final ImmutableList<String> explicitCppflags =
        ImmutableList.of("-explicit-cppflag");
    private static final ImmutableList<String> explicitCxxppflags =
        ImmutableList.of("-explicit-cxxppflag");

    private static final ImmutableList<String> empty = ImmutableList.of();

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
      return Arrays.asList(
          new Object[][] {
            {"test.i", cflags, explicitCompilerFlags, empty, empty},
            {"test.c", cflags, explicitCompilerFlags, cppflags, explicitCppflags},
            {"test.ii", cxxflags, explicitCompilerFlags, empty, empty},
            {"test.cpp", cxxflags, explicitCompilerFlags, cxxppflags, explicitCxxppflags},

            // asm do not have compiler specific flags, nor (non-as) file type specific flags.
            {"test.s", empty, empty, asppflags, explicitCppflags},

            // ObjC
            {"test.mi", cflags, explicitCompilerFlags, empty, empty},
            {"test.m", cflags, explicitCompilerFlags, cppflags, explicitCppflags},
            {"test.mii", cxxflags, explicitCompilerFlags, empty, empty},
            {"test.mm", cxxflags, explicitCompilerFlags, cxxppflags, explicitCxxppflags},
          });
    }

    @Parameterized.Parameter(0)
    public String sourceName;

    @Parameterized.Parameter(1)
    public ImmutableList<String> expectedTypeSpecificFlags;

    @Parameterized.Parameter(2)
    public ImmutableList<String> expectedCompilerFlags;

    @Parameterized.Parameter(3)
    public ImmutableList<String> expectedTypeSpecificPreprocessorFlags;

    @Parameterized.Parameter(4)
    public ImmutableList<String> expectedPreprocessorFlags;

    // Some common boilerplate.
    private ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    private SourcePathRuleFinder sourcePathRuleFinder = new SourcePathRuleFinder(graphBuilder);
    private SourcePathResolver sourcePathResolver =
        DefaultSourcePathResolver.from(sourcePathRuleFinder);
    private BuildContext context = FakeBuildContext.withSourcePathResolver(sourcePathResolver);
    private BuildTarget target = BuildTargetFactory.newInstance("//:target");
    private Joiner space = Joiner.on(" ");

    @Test
    public void forPreprocess() {
      CxxSource.Type sourceType =
          CxxSource.Type.fromExtension(MorePaths.getFileExtension(Paths.get(sourceName))).get();
      Assume.assumeTrue(sourceType.isPreprocessable());

      CxxPreprocessorInput cxxPreprocessorInput =
          CxxPreprocessorInput.builder()
              .putAllPreprocessorFlags(CxxSource.Type.C, StringArg.from(explicitCppflags))
              .putAllPreprocessorFlags(CxxSource.Type.OBJC, StringArg.from(explicitCppflags))
              .putAllPreprocessorFlags(
                  CxxSource.Type.ASSEMBLER_WITH_CPP, StringArg.from(explicitCppflags))
              .putAllPreprocessorFlags(CxxSource.Type.CXX, StringArg.from(explicitCxxppflags))
              .putAllPreprocessorFlags(CxxSource.Type.OBJCXX, StringArg.from(explicitCxxppflags))
              .build();
      BuckConfig buckConfig =
          FakeBuckConfig.builder()
              .setSections(
                  ImmutableMap.of(
                      "cxx",
                      ImmutableMap.<String, String>builder()
                          .put("asppflags", space.join(asppflags))
                          .put("cppflags", space.join(cppflags))
                          .put("cxxppflags", space.join(cxxppflags))
                          .build()))
              .setFilesystem(PROJECT_FILESYSTEM)
              .build();
      CxxPlatform platform = CxxPlatformUtils.build(new CxxBuckConfig(buckConfig));

      CxxSourceRuleFactory cxxSourceRuleFactory =
          CxxSourceRuleFactory.builder()
              .setProjectFilesystem(PROJECT_FILESYSTEM)
              .setBaseBuildTarget(target)
              .setActionGraphBuilder(graphBuilder)
              .setPathResolver(sourcePathResolver)
              .setRuleFinder(sourcePathRuleFinder)
              .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
              .setCxxPlatform(platform)
              .addCxxPreprocessorInput(cxxPreprocessorInput)
              .setPicType(PicType.PDC)
              .build();

      List<String> perFileFlags = ImmutableList.of("-per-file-flag", "-and-another-per-file-flag");
      CxxSource cSource = CxxSource.of(sourceType, FakeSourcePath.of(sourceName), perFileFlags);
      CxxPreprocessAndCompile cPreprocess =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(sourceName, cSource);
      ImmutableList<String> cPreprocessCommand =
          Arg.stringify(
              cPreprocess
                  .getPreprocessorDelegate()
                  .get()
                  .getCommand(CxxToolFlags.of(), Optional.empty(), sourcePathResolver),
              sourcePathResolver);
      assertContains(cPreprocessCommand, expectedTypeSpecificPreprocessorFlags);
      assertContains(cPreprocessCommand, expectedPreprocessorFlags);
      assertContains(cPreprocessCommand, perFileFlags);
    }

    @Test
    public void forCompile() {
      CxxSource.Type sourceType =
          CxxSource.Type.fromExtension(MorePaths.getFileExtension(Paths.get(sourceName))).get();

      BuckConfig buckConfig =
          FakeBuckConfig.builder()
              .setSections(
                  ImmutableMap.of(
                      "cxx",
                      ImmutableMap.<String, String>builder()
                          .put("asflags", space.join(asflags))
                          .put("cflags", space.join(cflags))
                          .put("cxxflags", space.join(cxxflags))
                          .build()))
              .setFilesystem(PROJECT_FILESYSTEM)
              .build();
      CxxPlatform platform = CxxPlatformUtils.build(new CxxBuckConfig(buckConfig));

      CxxSourceRuleFactory cxxSourceRuleFactory =
          CxxSourceRuleFactory.builder()
              .setProjectFilesystem(PROJECT_FILESYSTEM)
              .setBaseBuildTarget(target)
              .setActionGraphBuilder(graphBuilder)
              .setPathResolver(sourcePathResolver)
              .setRuleFinder(sourcePathRuleFinder)
              .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
              .setCxxPlatform(platform)
              .setCompilerFlags(CxxFlags.toLanguageFlags(StringArg.from(expectedCompilerFlags)))
              .setPicType(PicType.PDC)
              .build();

      List<String> perFileFlags = ImmutableList.of("-per-file-flag");
      CxxSource source = CxxSource.of(sourceType, FakeSourcePath.of(sourceName), perFileFlags);
      CxxPreprocessAndCompile rule;
      if (source.getType().isPreprocessable()) {
        rule = cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(sourceName, source);
      } else {
        rule = cxxSourceRuleFactory.requireCompileBuildRule(sourceName, source);
      }
      ImmutableList<String> command = rule.makeMainStep(context, false).getCommand();
      assertContains(command, expectedCompilerFlags);
      assertContains(command, expectedTypeSpecificFlags);
      assertContains(command, asflags);
      assertContains(command, perFileFlags);
    }
  }

  @RunWith(Parameterized.class)
  public static class LanguageFlagsArePassed {
    @Parameterized.Parameters(name = "{0} -> {1}")
    public static Collection<Object[]> data() {
      return Arrays.asList(
          new Object[][] {
            {"foo/bar.ii", "c++-cpp-output"},
            {"foo/bar.mi", "objective-c-cpp-output"},
            {"foo/bar.mii", "objective-c++-cpp-output"},
            {"foo/bar.i", "cpp-output"},
          });
    }

    @Parameterized.Parameter(0)
    public String name;

    @Parameterized.Parameter(1)
    public String expected;

    @Test
    public void test() {
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
      BuildContext context = FakeBuildContext.withSourcePathResolver(sourcePathResolver);
      BuildTarget target = BuildTargetFactory.newInstance("//:target");
      CxxSourceRuleFactory cxxSourceRuleFactory =
          CxxSourceRuleFactory.builder()
              .setProjectFilesystem(PROJECT_FILESYSTEM)
              .setBaseBuildTarget(target)
              .setActionGraphBuilder(graphBuilder)
              .setPathResolver(sourcePathResolver)
              .setRuleFinder(ruleFinder)
              .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
              .setCxxPlatform(CXX_PLATFORM)
              .setPicType(PicType.PDC)
              .build();

      SourcePath input = FakeSourcePath.of(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
      CxxSource cxxSource =
          CxxSource.of(
              CxxSource.Type.fromExtension(MorePaths.getFileExtension(Paths.get(name))).get(),
              input,
              ImmutableList.of());
      CxxPreprocessAndCompile cxxCompile =
          cxxSourceRuleFactory.requireCompileBuildRule(name, cxxSource);
      assertThat(cxxCompile.makeMainStep(context, false).getCommand(), hasItems("-x", expected));
    }
  }
}
