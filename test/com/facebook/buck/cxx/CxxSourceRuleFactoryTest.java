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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class CxxSourceRuleFactoryTest {

  private static final ProjectFilesystem PROJECT_FILESYSTEM = new FakeProjectFilesystem();

  private static final CxxPlatform CXX_PLATFORM = DefaultCxxPlatforms.build(
      new CxxBuckConfig(FakeBuckConfig.builder().build()));

  private static <T> void assertContains(ImmutableList<T> container, Iterable<T> items) {
    for (T item : items) {
      assertThat(container, Matchers.hasItem(item));
    }
  }

  public static class CxxSourceRuleFactoryTests {
    private static FakeBuildRule createFakeBuildRule(
        String target,
        SourcePathResolver resolver,
        BuildRule... deps) {
      return new FakeBuildRule(
          new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
              .setDeclaredDeps(ImmutableSortedSet.copyOf(deps))
              .build(),
          resolver);
    }

    @Test
    public void createPreprocessBuildRulePropagatesCxxPreprocessorDeps() {
      BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
      BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
      BuildRuleResolver resolver =
          new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
      SourcePathResolver pathResolver = new SourcePathResolver(resolver);

      FakeBuildRule dep = resolver.addToIndex(new FakeBuildRule("//:dep1", pathResolver));

      CxxPreprocessorInput cxxPreprocessorInput =
          CxxPreprocessorInput.builder()
              .addRules(dep.getBuildTarget())
              .build();

      CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactory.builder()
          .setParams(params)
          .setResolver(resolver)
          .setPathResolver(pathResolver)
          .setCxxPlatform(CXX_PLATFORM)
          .addCxxPreprocessorInput(cxxPreprocessorInput)
          .setPicType(CxxSourceRuleFactory.PicType.PDC)
          .build();

      String name = "foo/bar.cpp";
      SourcePath input = new PathSourcePath(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
      CxxSource cxxSource = CxxSource.of(
          CxxSource.Type.CXX,
          input,
          ImmutableList.<String>of());

      BuildRule cxxPreprocess =
          cxxSourceRuleFactory.requirePreprocessBuildRule(
              name,
              cxxSource);
      assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxPreprocess.getDeps());
      cxxPreprocess =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
              name,
              cxxSource,
              CxxPreprocessMode.SEPARATE);
      assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxPreprocess.getDeps());
    }

    @Test
    public void preprocessFlagsFromPlatformArePropagated() {
      BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
      BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
      BuildRuleResolver resolver =
          new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
      SourcePathResolver pathResolver = new SourcePathResolver(resolver);

      ImmutableList<String> platformFlags = ImmutableList.of("-some", "-flags");
      CxxPlatform platform = DefaultCxxPlatforms.build(
          new CxxBuckConfig(
              FakeBuckConfig.builder().setSections(
                  ImmutableMap.of(
                      "cxx", ImmutableMap.of("cxxppflags", Joiner.on(" ").join(platformFlags))))
                  .build()));

      CxxPreprocessorInput cxxPreprocessorInput = CxxPreprocessorInput.EMPTY;

      CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactory.builder()
          .setParams(params)
          .setResolver(resolver)
          .setPathResolver(pathResolver)
          .setCxxPlatform(platform)
          .addCxxPreprocessorInput(cxxPreprocessorInput)
          .setPicType(CxxSourceRuleFactory.PicType.PDC)
          .build();

      String name = "source.cpp";
      CxxSource cxxSource = CxxSource.of(
          CxxSource.Type.CXX,
          new FakeSourcePath(name),
          ImmutableList.<String>of());

      // Verify that platform flags make it to the compile rule.
      CxxPreprocessAndCompile cxxPreprocess =
          cxxSourceRuleFactory.requirePreprocessBuildRule(
              name,
              cxxSource);
      assertNotEquals(
          -1,
          Collections.indexOfSubList(
              cxxPreprocess.getPreprocessorDelegate().get().getCommand(CxxToolFlags.of()),
              platformFlags));
      CxxPreprocessAndCompile cxxPreprocessAndCompile =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
              name,
              cxxSource,
              CxxPreprocessMode.SEPARATE);
      assertNotEquals(
          -1,
          Collections.indexOfSubList(
              cxxPreprocessAndCompile.getPreprocessorDelegate().get().getCommand(CxxToolFlags.of()),
              platformFlags));
    }

    @Test
    public void createCompileBuildRulePropagatesBuildRuleSourcePathDeps() {
      BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
      BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
      BuildRuleResolver resolver =
          new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());

      FakeBuildRule dep = createFakeBuildRule("//:test", new SourcePathResolver(resolver));
      resolver.addToIndex(dep);
      SourcePath input = new BuildTargetSourcePath(dep.getBuildTarget());
      CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactory.builder()
          .setParams(params)
          .setResolver(resolver)
          .setPathResolver(new SourcePathResolver(resolver))
          .setCxxPlatform(CXX_PLATFORM)
          .setPicType(CxxSourceRuleFactory.PicType.PDC)
          .build();

      String nameCompile = "foo/bar.ii";
      CxxSource cxxSourceCompile = CxxSource.of(
          CxxSource.Type.CXX_CPP_OUTPUT,
          input,
          ImmutableList.<String>of());
      CxxPreprocessAndCompile cxxCompile =
          cxxSourceRuleFactory.requireCompileBuildRule(
              nameCompile,
              cxxSourceCompile);
      assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxCompile.getDeps());

      String namePreprocessAndCompile = "foo/bar.cpp";
      CxxSource cxxSourcePreprocessAndCompile = CxxSource.of(
          CxxSource.Type.CXX,
          input,
          ImmutableList.<String>of());
      CxxPreprocessAndCompile cxxPreprocessAndCompile =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
              namePreprocessAndCompile,
              cxxSourcePreprocessAndCompile,
              CxxPreprocessMode.SEPARATE);
      assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxPreprocessAndCompile.getDeps());
    }

    @Test
    @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
    public void createCompileBuildRulePicOption() {
      BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
      BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
      BuildRuleResolver resolver =
          new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());

      CxxSourceRuleFactory.Builder cxxSourceRuleFactoryBuilder = CxxSourceRuleFactory.builder()
          .setParams(params)
          .setResolver(resolver)
          .setPathResolver(new SourcePathResolver(resolver))
          .setCxxPlatform(CXX_PLATFORM);
      CxxSourceRuleFactory cxxSourceRuleFactoryPDC = cxxSourceRuleFactoryBuilder
          .setPicType(CxxSourceRuleFactory.PicType.PDC)
          .build();
      CxxSourceRuleFactory cxxSourceRuleFactoryPIC = cxxSourceRuleFactoryBuilder
          .setPicType(CxxSourceRuleFactory.PicType.PIC)
          .build();

      String name = "foo/bar.ii";
      CxxSource cxxSource = CxxSource.of(
          CxxSource.Type.CXX_CPP_OUTPUT,
          new FakeSourcePath(name),
          ImmutableList.<String>of());

      // Verify building a non-PIC compile rule does *not* have the "-fPIC" flag and has the
      // expected compile target.
      CxxPreprocessAndCompile noPicCompile =
          cxxSourceRuleFactoryPDC.requireCompileBuildRule(name, cxxSource);
      assertFalse(noPicCompile.makeMainStep().getCommand().contains("-fPIC"));
      assertEquals(
          cxxSourceRuleFactoryPDC.createCompileBuildTarget(name),
          noPicCompile.getBuildTarget());

      // Verify building a PIC compile rule *does* have the "-fPIC" flag and has the
      // expected compile target.
      CxxPreprocessAndCompile picCompile =
          cxxSourceRuleFactoryPIC.requireCompileBuildRule(name, cxxSource);
      assertTrue(picCompile.makeMainStep().getCommand().contains("-fPIC"));
      assertEquals(
          cxxSourceRuleFactoryPIC.createCompileBuildTarget(name),
          picCompile.getBuildTarget());

      name = "foo/bar.cpp";
      cxxSource = CxxSource.of(
          CxxSource.Type.CXX,
          new FakeSourcePath(name),
          ImmutableList.<String>of());

      // Verify building a non-PIC compile rule does *not* have the "-fPIC" flag and has the
      // expected compile target.
      CxxPreprocessAndCompile noPicPreprocessAndCompile =
          cxxSourceRuleFactoryPDC.requirePreprocessAndCompileBuildRule(
              name,
              cxxSource,
              CxxPreprocessMode.SEPARATE);
      assertFalse(noPicPreprocessAndCompile.makeMainStep().getCommand().contains("-fPIC"));
      assertEquals(
          cxxSourceRuleFactoryPDC.createCompileBuildTarget(name),
          noPicPreprocessAndCompile.getBuildTarget());

      // Verify building a PIC compile rule *does* have the "-fPIC" flag and has the
      // expected compile target.
      CxxPreprocessAndCompile picPreprocessAndCompile =
          cxxSourceRuleFactoryPIC.requirePreprocessAndCompileBuildRule(
              name,
              cxxSource,
              CxxPreprocessMode.SEPARATE);
      assertTrue(picPreprocessAndCompile.makeMainStep().getCommand().contains("-fPIC"));
      assertEquals(
          cxxSourceRuleFactoryPIC.createCompileBuildTarget(name),
          picPreprocessAndCompile.getBuildTarget());
    }

    @Test
    public void checkPrefixHeaderIsIncluded() {
      BuildRuleResolver buildRuleResolver =
          new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
      BuildTarget target = BuildTargetFactory.newInstance("//:target");
      BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
      ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

      BuckConfig buckConfig = FakeBuckConfig.builder().setFilesystem(filesystem).build();
      CxxPlatform platform = DefaultCxxPlatforms.build(new CxxBuckConfig(buckConfig));

      String prefixHeaderName = "test.pch";
      SourcePath prefixHeaderSourcePath = new FakeSourcePath(filesystem, prefixHeaderName);

      CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactory.builder()
          .setParams(params)
          .setResolver(buildRuleResolver)
          .setPathResolver(new SourcePathResolver(buildRuleResolver))
          .setCxxPlatform(platform)
          .setPrefixHeader(prefixHeaderSourcePath)
          .setPicType(CxxSourceRuleFactory.PicType.PDC)
          .build();

      String objcSourceName = "test.m";
      CxxSource objcSource = CxxSource.of(
          CxxSource.Type.OBJC,
          new FakeSourcePath(objcSourceName),
          ImmutableList.<String>of());
      CxxPreprocessAndCompile objcPreprocessAndCompile =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
              objcSourceName,
              objcSource,
              CxxPreprocessMode.SEPARATE);

      ImmutableList<String> explicitPrefixHeaderRelatedFlags = ImmutableList.of(
          "-include", filesystem.resolve(prefixHeaderName).toString());

      CxxPreprocessAndCompileStep step = objcPreprocessAndCompile.makeMainStep();
      assertContains(step.getCommand(), explicitPrefixHeaderRelatedFlags);
    }

    @Test
    public void duplicateRuleFetchedFromResolver() {
      BuildRuleResolver buildRuleResolver =
          new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
      BuildTarget target = BuildTargetFactory.newInstance("//:target");
      BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
      ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

      BuckConfig buckConfig = FakeBuckConfig.builder().setFilesystem(filesystem).build();
      CxxPlatform platform = DefaultCxxPlatforms.build(new CxxBuckConfig(buckConfig));

      CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactory.builder()
          .setParams(params)
          .setResolver(buildRuleResolver)
          .setPathResolver(new SourcePathResolver(buildRuleResolver))
          .setCxxPlatform(platform)
          .setPicType(CxxSourceRuleFactory.PicType.PDC)
          .build();

      String objcSourceName = "test.m";
      CxxSource objcSource = CxxSource.of(
          CxxSource.Type.OBJC,
          new FakeSourcePath(objcSourceName),
          ImmutableList.<String>of());
      CxxPreprocessAndCompile objcCompile =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
              objcSourceName,
              objcSource,
              CxxPreprocessMode.SEPARATE);

      // Make sure we can get the same build rule twice.
      CxxPreprocessAndCompile objcCompile2 =
          cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
              objcSourceName,
              objcSource,
              CxxPreprocessMode.SEPARATE);

      assertEquals(objcCompile.getBuildTarget(), objcCompile2.getBuildTarget());
    }
  }

  @RunWith(Parameterized.class)
  public static class CorrectFlagsAreUsedForCompileAndPreprocessBuildRules {

    private static final SourcePath as = new FakeSourcePath("as");
    private static final SourcePath cc = new FakeSourcePath("cc");
    private static final SourcePath cpp = new FakeSourcePath("cpp");
    private static final SourcePath cxx = new FakeSourcePath("cxx");
    private static final SourcePath cxxpp = new FakeSourcePath("cxxpp");

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
      return Arrays.asList(new Object[][]{
          {"test.i", cflags, explicitCompilerFlags, empty, empty},
          {"test.c", cflags, explicitCompilerFlags, cppflags, explicitCppflags},
          {"test.ii", cxxflags, explicitCompilerFlags, empty, empty},
          {"test.cpp", cxxflags, explicitCompilerFlags, cxxppflags, explicitCxxppflags},

          // asm do not have compiler specific flags, nor (non-as) file type specific flags.
          {"test.s", empty, empty, empty, empty},
          {"test.S", empty, empty, asppflags, explicitCppflags},

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
    private BuildRuleResolver buildRuleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    private SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);
    private BuildTarget target = BuildTargetFactory.newInstance("//:target");
    private BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    private Joiner space = Joiner.on(" ");

    @Test
    public void forPreprocess() {
      CxxSource.Type sourceType =
          CxxSource.Type.fromExtension(MorePaths.getFileExtension(Paths.get(sourceName))).get();
      Assume.assumeTrue(sourceType.isPreprocessable());

      CxxPreprocessorInput cxxPreprocessorInput =
          CxxPreprocessorInput.builder()
              .putAllPreprocessorFlags(CxxSource.Type.C, explicitCppflags)
              .putAllPreprocessorFlags(CxxSource.Type.OBJC, explicitCppflags)
              .putAllPreprocessorFlags(CxxSource.Type.ASSEMBLER_WITH_CPP, explicitCppflags)
              .putAllPreprocessorFlags(CxxSource.Type.CXX, explicitCxxppflags)
              .putAllPreprocessorFlags(CxxSource.Type.OBJCXX, explicitCxxppflags)
              .build();
      BuckConfig buckConfig = FakeBuckConfig.builder()
          .setSections(
              ImmutableMap.of(
                  "cxx", ImmutableMap.<String, String>builder()
                      .put("asppflags", space.join(asppflags))
                      .put("cpp", sourcePathResolver.deprecatedGetPath(cpp).toString())
                      .put("cppflags", space.join(cppflags))
                      .put("cxxpp", sourcePathResolver.deprecatedGetPath(cxxpp).toString())
                      .put("cxxppflags", space.join(cxxppflags))
                      .build()))
          .setFilesystem(PROJECT_FILESYSTEM)
          .build();
      CxxPlatform platform = DefaultCxxPlatforms.build(new CxxBuckConfig(buckConfig));

      CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactory.builder()
          .setParams(params)
          .setResolver(buildRuleResolver)
          .setPathResolver(sourcePathResolver)
          .setCxxPlatform(platform)
          .addCxxPreprocessorInput(cxxPreprocessorInput)
          .setPicType(CxxSourceRuleFactory.PicType.PDC)
          .build();

      List<String> perFileFlags = ImmutableList.of("-per-file-flag", "-and-another-per-file-flag");
      CxxSource cSource = CxxSource.of(sourceType, new FakeSourcePath(sourceName), perFileFlags);
      CxxPreprocessAndCompile cPreprocess =
          cxxSourceRuleFactory.requirePreprocessBuildRule(sourceName, cSource);
      ImmutableList<String> cPreprocessCommand =
          cPreprocess.getPreprocessorDelegate().get().getCommand(CxxToolFlags.of());
      assertContains(cPreprocessCommand, expectedTypeSpecificPreprocessorFlags);
      assertContains(cPreprocessCommand, expectedPreprocessorFlags);
      assertContains(cPreprocessCommand, perFileFlags);
    }

    @Test
    public void forCompile() {
      CxxSource.Type sourceType =
          CxxSource.Type.fromExtension(MorePaths.getFileExtension(Paths.get(sourceName))).get();

      BuckConfig buckConfig = FakeBuckConfig.builder()
          .setSections(
              ImmutableMap.of(
                  "cxx", ImmutableMap.<String, String>builder()
                      .put("as", sourcePathResolver.deprecatedGetPath(as).toString())
                      .put("asflags", space.join(asflags))
                      .put("cc", sourcePathResolver.deprecatedGetPath(cc).toString())
                      .put("cflags", space.join(cflags))
                      .put("cxx", sourcePathResolver.deprecatedGetPath(cxx).toString())
                      .put("cxxflags", space.join(cxxflags))
                      .build()))
          .setFilesystem(PROJECT_FILESYSTEM)
          .build();
      CxxPlatform platform = DefaultCxxPlatforms.build(
          new CxxBuckConfig(buckConfig));

      CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactory.builder()
          .setParams(params)
          .setResolver(buildRuleResolver)
          .setPathResolver(sourcePathResolver)
          .setCxxPlatform(platform)
          .setCompilerFlags(explicitCompilerFlags)
          .setPicType(CxxSourceRuleFactory.PicType.PDC)
          .build();

      List<String> perFileFlags = ImmutableList.of("-per-file-flag");
      CxxSource source = CxxSource.of(sourceType, new FakeSourcePath(sourceName), perFileFlags);
      CxxPreprocessAndCompile rule;
      if (source.getType().isPreprocessable()) {
        rule = cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            sourceName,
            source,
            CxxPreprocessMode.SEPARATE);
      } else {
        rule = cxxSourceRuleFactory.requireCompileBuildRule(sourceName, source);
      }
      ImmutableList<String> command = rule.makeMainStep().getCommand();
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
      return Arrays.asList(new Object[][]{
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
      BuildRuleResolver buildRuleResolver =
          new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
      SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);
      BuildTarget target = BuildTargetFactory.newInstance("//:target");
      BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
      CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactory.builder()
          .setParams(params)
          .setResolver(buildRuleResolver)
          .setPathResolver(sourcePathResolver)
          .setCxxPlatform(CXX_PLATFORM)
          .setPicType(CxxSourceRuleFactory.PicType.PDC)
          .build();

      SourcePath input = new PathSourcePath(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
      CxxSource cxxSource = CxxSource.of(
          CxxSource.Type.fromExtension(MorePaths.getFileExtension(Paths.get(name))).get(),
          input,
          ImmutableList.<String>of());
      CxxPreprocessAndCompile cxxCompile =
          cxxSourceRuleFactory.createCompileBuildRule(name, cxxSource);
      assertThat(cxxCompile.makeMainStep().getCommand(), Matchers.hasItems("-x", expected));
    }
  }

}
