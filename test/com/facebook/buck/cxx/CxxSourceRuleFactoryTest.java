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
import com.facebook.buck.cli.FakeBuckConfig;
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
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class CxxSourceRuleFactoryTest {

  private static final ProjectFilesystem PROJECT_FILESYSTEM = new FakeProjectFilesystem();

  private static final CxxPlatform CXX_PLATFORM = DefaultCxxPlatforms.build(
      new CxxBuckConfig(FakeBuckConfig.builder().build()));

  private static <T> void assertContains(ImmutableList<T> container, Iterable<T> items) {
    for (T item : items) {
      assertThat(container, Matchers.hasItem(item));
    }
  }

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
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    FakeBuildRule dep = resolver.addToIndex(
        new FakeBuildRule(
            "//:dep1",
            new SourcePathResolver(new BuildRuleResolver())));

    CxxPreprocessorInput cxxPreprocessorInput =
        CxxPreprocessorInput.builder()
            .addRules(dep.getBuildTarget())
            .build();

    CxxSourceRuleFactory cxxSourceRuleFactory =
        new CxxSourceRuleFactory(
            params,
            resolver,
            pathResolver,
            CXX_PLATFORM,
            ImmutableList.of(cxxPreprocessorInput),
            ImmutableList.<String>of(),
            Optional.<SourcePath>absent());

    String name = "foo/bar.cpp";
    SourcePath input = new PathSourcePath(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
    CxxSource cxxSource = CxxSource.of(
        CxxSource.Type.CXX,
        input,
        ImmutableList.<String>of());

    BuildRule cxxPreprocess =
        cxxSourceRuleFactory.requirePreprocessBuildRule(
            resolver,
            name,
            cxxSource,
            CxxSourceRuleFactory.PicType.PDC);
    assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxPreprocess.getDeps());
    cxxPreprocess =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            resolver,
            name,
            cxxSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxPreprocess.getDeps());
  }

  @Test
  public void preprocessFlagsFromPlatformArePropagated() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableList<String> platformFlags = ImmutableList.of("-some", "-flags");
    CxxPlatform platform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of(
                    "cxx", ImmutableMap.of("cxxppflags", Joiner.on(" ").join(platformFlags))))
                .build()));

    CxxPreprocessorInput cxxPreprocessorInput = CxxPreprocessorInput.EMPTY;

    CxxSourceRuleFactory cxxSourceRuleFactory =
        new CxxSourceRuleFactory(
            params,
            resolver,
            pathResolver,
            platform,
            ImmutableList.of(cxxPreprocessorInput),
            ImmutableList.<String>of(),
            Optional.<SourcePath>absent());

    String name = "source.cpp";
    CxxSource cxxSource = CxxSource.of(
        CxxSource.Type.CXX,
        new FakeSourcePath(name),
        ImmutableList.<String>of());

    // Verify that platform flags make it to the compile rule.
    CxxPreprocessAndCompile cxxPreprocess =
        cxxSourceRuleFactory.requirePreprocessBuildRule(
            resolver,
            name,
            cxxSource,
            CxxSourceRuleFactory.PicType.PDC);
    assertNotEquals(
        -1,
        Collections.indexOfSubList(
            cxxPreprocess.getPreprocessorDelegate().get().getPreprocessorCommand(),
            platformFlags));
    CxxPreprocessAndCompile cxxPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            resolver,
            name,
            cxxSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertNotEquals(
        -1,
        Collections.indexOfSubList(
            cxxPreprocessAndCompile.getPreprocessorDelegate().get().getPreprocessorCommand(),
            platformFlags));
  }

  @Test
  public void checkCorrectFlagsAreUsedForPreprocessBuildRules() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    Joiner space = Joiner.on(" ");

    ImmutableList<String> explicitCppflags = ImmutableList.of("-explicit-cppflag");
    ImmutableList<String> explicitCxxppflags = ImmutableList.of("-explicit-cxxppflag");
    CxxPreprocessorInput cxxPreprocessorInput =
        CxxPreprocessorInput.builder()
            .putAllPreprocessorFlags(CxxSource.Type.C, explicitCppflags)
            .putAllPreprocessorFlags(CxxSource.Type.CXX, explicitCxxppflags)
            .build();

    ImmutableList<String> asppflags = ImmutableList.of("-asppflag", "-asppflag");

    SourcePath cpp = new FakeSourcePath("cpp");
    ImmutableList<String> cppflags = ImmutableList.of("-cppflag", "-cppflag");

    SourcePath cxxpp = new FakeSourcePath("cxxpp");
    ImmutableList<String> cxxppflags = ImmutableList.of("-cxxppflag", "-cxxppflag");

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
        .setFilesystem(filesystem)
        .build();
    CxxPlatform platform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(buckConfig));

    CxxSourceRuleFactory cxxSourceRuleFactory =
        new CxxSourceRuleFactory(
            params,
            buildRuleResolver,
            sourcePathResolver,
            platform,
            ImmutableList.of(cxxPreprocessorInput),
            ImmutableList.<String>of(),
            Optional.<SourcePath>absent());

    String cSourceName = "test.c";
    List<String> perFileFlagsForTestC =
        ImmutableList.of("-per-file-flag-for-c-file", "-and-another-one");
    CxxSource cSource = CxxSource.of(
        CxxSource.Type.C,
        new FakeSourcePath(cSourceName),
        perFileFlagsForTestC);
    CxxPreprocessAndCompile cPreprocess =
        cxxSourceRuleFactory.requirePreprocessBuildRule(
            buildRuleResolver,
            cSourceName,
            cSource,
            CxxSourceRuleFactory.PicType.PDC);
    ImmutableList<String> cPreprocessCommand =
        cPreprocess.getPreprocessorDelegate().get().getPreprocessorCommand();
    assertContains(cPreprocessCommand, explicitCppflags);
    assertContains(cPreprocessCommand, cppflags);
    assertContains(cPreprocessCommand, perFileFlagsForTestC);
    CxxPreprocessAndCompile cPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            buildRuleResolver,
            cSourceName,
            cSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertContains(cPreprocessAndCompile.getRuleCompilerFlags().get(), perFileFlagsForTestC);

    String cxxSourceName = "test.cpp";
    List<String> perFileFlagsForTestCpp =
        ImmutableList.of("-per-file-flag-for-cpp-file");
    CxxSource cxxSource = CxxSource.of(
        CxxSource.Type.CXX,
        new FakeSourcePath(cxxSourceName),
        perFileFlagsForTestCpp);
    CxxPreprocessAndCompile cxxPreprocess =
        cxxSourceRuleFactory.requirePreprocessBuildRule(
            buildRuleResolver,
            cxxSourceName,
            cxxSource,
            CxxSourceRuleFactory.PicType.PDC);
    ImmutableList<String> cxxPreprocessCommand =
        cxxPreprocess.getPreprocessorDelegate().get().getPreprocessorCommand();
    assertContains(cxxPreprocessCommand, explicitCxxppflags);
    assertContains(cxxPreprocessCommand, cxxppflags);
    assertContains(cxxPreprocessCommand, perFileFlagsForTestCpp);
    CxxPreprocessAndCompile cxxPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            buildRuleResolver,
            cxxSourceName,
            cxxSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertContains(cxxPreprocessAndCompile.getRuleCompilerFlags().get(), perFileFlagsForTestCpp);

    String assemblerWithCppSourceName = "test.S";
    List<String> perFileFlagsForTestS =
        ImmutableList.of("-a-flag-for-s-file", "-another-one", "-one-more");
    CxxSource assemblerWithCppSource = CxxSource.of(
        CxxSource.Type.ASSEMBLER_WITH_CPP,
        new FakeSourcePath(assemblerWithCppSourceName),
        perFileFlagsForTestS);
    CxxPreprocessAndCompile assemblerWithCppPreprocess =
        cxxSourceRuleFactory.requirePreprocessBuildRule(
            buildRuleResolver,
            assemblerWithCppSourceName,
            assemblerWithCppSource,
            CxxSourceRuleFactory.PicType.PDC);
    assertContains(
        assemblerWithCppPreprocess.getPreprocessorDelegate().get().getPreprocessorCommand(),
         asppflags);
    assertContains(
        assemblerWithCppPreprocess.getPreprocessorDelegate().get().getPreprocessorCommand(),
        perFileFlagsForTestS);
    CxxPreprocessAndCompile assemblerWithCppPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            buildRuleResolver,
            assemblerWithCppSourceName,
            assemblerWithCppSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertContains(
        assemblerWithCppPreprocessAndCompile.getRuleCompilerFlags().get(),
        perFileFlagsForTestS);
  }

  @Test
  public void createCompileBuildRulePropagatesBuildRuleSourcePathDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    BuildRuleResolver resolver = new BuildRuleResolver();

    FakeBuildRule dep = createFakeBuildRule("//:test", new SourcePathResolver(resolver));
    resolver.addToIndex(dep);
    SourcePath input = new BuildTargetSourcePath(dep.getBuildTarget());
    CxxSourceRuleFactory cxxSourceRuleFactory =
        new CxxSourceRuleFactory(
            params,
            resolver,
            new SourcePathResolver(resolver),
            CXX_PLATFORM,
            ImmutableList.<CxxPreprocessorInput>of(),
            ImmutableList.<String>of(),
            Optional.<SourcePath>absent());

    String nameCompile = "foo/bar.ii";
    CxxSource cxxSourceCompile = CxxSource.of(
        CxxSource.Type.CXX_CPP_OUTPUT,
        input,
        ImmutableList.<String>of());
    CxxPreprocessAndCompile cxxCompile =
        cxxSourceRuleFactory.requireCompileBuildRule(
            resolver,
            nameCompile,
            cxxSourceCompile,
            CxxSourceRuleFactory.PicType.PDC);
    assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxCompile.getDeps());

    String namePreprocessAndCompile = "foo/bar.cpp";
    CxxSource cxxSourcePreprocessAndCompile = CxxSource.of(
        CxxSource.Type.CXX,
        input,
        ImmutableList.<String>of());
    CxxPreprocessAndCompile cxxPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            resolver,
            namePreprocessAndCompile,
            cxxSourcePreprocessAndCompile,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxPreprocessAndCompile.getDeps());
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void createCompileBuildRulePicOption() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    BuildRuleResolver resolver = new BuildRuleResolver();

    CxxSourceRuleFactory cxxSourceRuleFactory =
        new CxxSourceRuleFactory(
            params,
            resolver,
            new SourcePathResolver(resolver),
            CXX_PLATFORM,
            ImmutableList.<CxxPreprocessorInput>of(),
            ImmutableList.<String>of(),
            Optional.<SourcePath>absent());

    String name = "foo/bar.ii";
    CxxSource cxxSource = CxxSource.of(
        CxxSource.Type.CXX_CPP_OUTPUT,
        new FakeSourcePath(name),
        ImmutableList.<String>of());

    // Verify building a non-PIC compile rule does *not* have the "-fPIC" flag and has the
    // expected compile target.
    CxxPreprocessAndCompile noPicCompile =
        cxxSourceRuleFactory.requireCompileBuildRule(
            resolver,
            name,
            cxxSource,
            CxxSourceRuleFactory.PicType.PDC);
    assertFalse(noPicCompile.getPlatformCompilerFlags().get().contains("-fPIC"));
    assertEquals(
        cxxSourceRuleFactory.createCompileBuildTarget(
            name,
            CxxSourceRuleFactory.PicType.PDC),
        noPicCompile.getBuildTarget());

    // Verify building a PIC compile rule *does* have the "-fPIC" flag and has the
    // expected compile target.
    CxxPreprocessAndCompile picCompile =
        cxxSourceRuleFactory.requireCompileBuildRule(
            resolver,
            name,
            cxxSource,
            CxxSourceRuleFactory.PicType.PIC);
    assertTrue(picCompile.getPlatformCompilerFlags().get().contains("-fPIC"));
    assertEquals(
        cxxSourceRuleFactory.createCompileBuildTarget(
            name,
            CxxSourceRuleFactory.PicType.PIC),
        picCompile.getBuildTarget());

    name = "foo/bar.cpp";
    cxxSource = CxxSource.of(
        CxxSource.Type.CXX,
        new FakeSourcePath(name),
        ImmutableList.<String>of());

    // Verify building a non-PIC compile rule does *not* have the "-fPIC" flag and has the
    // expected compile target.
    CxxPreprocessAndCompile noPicPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            resolver,
            name,
            cxxSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertFalse(noPicPreprocessAndCompile.getPlatformCompilerFlags().get().contains("-fPIC"));
    assertEquals(
        cxxSourceRuleFactory.createCompileBuildTarget(
            name,
            CxxSourceRuleFactory.PicType.PDC),
        noPicPreprocessAndCompile.getBuildTarget());

    // Verify building a PIC compile rule *does* have the "-fPIC" flag and has the
    // expected compile target.
    CxxPreprocessAndCompile picPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            resolver,
            name,
            cxxSource,
            CxxSourceRuleFactory.PicType.PIC,
            CxxPreprocessMode.SEPARATE);
    assertTrue(picPreprocessAndCompile.getPlatformCompilerFlags().get().contains("-fPIC"));
    assertEquals(
        cxxSourceRuleFactory.createCompileBuildTarget(
            name,
            CxxSourceRuleFactory.PicType.PIC),
        picPreprocessAndCompile.getBuildTarget());
  }

  @Test
  public void compilerFlagsFromPlatformArePropagated() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    BuildRuleResolver resolver = new BuildRuleResolver();

    ImmutableList<String> platformFlags = ImmutableList.of("-some", "-flags");
    CxxPlatform platform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of(
                    "cxx", ImmutableMap.of("cxxflags", Joiner.on(" ").join(platformFlags))))
                .build()));

    CxxSourceRuleFactory cxxSourceRuleFactory =
        new CxxSourceRuleFactory(
            params,
            resolver,
            new SourcePathResolver(resolver),
            platform,
            ImmutableList.<CxxPreprocessorInput>of(),
            ImmutableList.<String>of(),
            Optional.<SourcePath>absent());

    String name = "source.ii";
    CxxSource cxxSource = CxxSource.of(
        CxxSource.Type.CXX_CPP_OUTPUT,
        new FakeSourcePath(name),
        ImmutableList.<String>of());

    // Verify that platform flags make it to the compile rule.
    CxxPreprocessAndCompile cxxCompile =
        cxxSourceRuleFactory.requireCompileBuildRule(
            resolver,
            name,
            cxxSource,
            CxxSourceRuleFactory.PicType.PDC);
    assertNotEquals(
        -1,
        Collections.indexOfSubList(cxxCompile.getPlatformCompilerFlags().get(), platformFlags));

    name = "source.cpp";
    cxxSource = CxxSource.of(
        CxxSource.Type.CXX,
        new FakeSourcePath(name),
        ImmutableList.<String>of());

    // Verify that platform flags make it to the compile rule.
    CxxPreprocessAndCompile cxxPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            resolver,
            name,
            cxxSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertNotEquals(
        -1,
        Collections.indexOfSubList(
            cxxPreprocessAndCompile.getPreprocessorDelegate().get().getPreprocessorCommand(),
            platformFlags));
  }

  @Test
  public void checkCorrectFlagsAreUsedForCompileBuildRules() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    Joiner space = Joiner.on(" ");

    ImmutableList<String> explicitCompilerFlags = ImmutableList.of("-explicit-compilerflag");

    SourcePath as = new FakeSourcePath("as");
    ImmutableList<String> asflags = ImmutableList.of("-asflag", "-asflag");

    SourcePath cc = new FakeSourcePath("cc");
    ImmutableList<String> cflags = ImmutableList.of("-cflag", "-cflag");

    SourcePath cxx = new FakeSourcePath("cxx");
    ImmutableList<String> cxxflags = ImmutableList.of("-cxxflag", "-cxxflag");

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
        .setFilesystem(filesystem)
        .build();
    CxxPlatform platform = DefaultCxxPlatforms.build(
        new CxxBuckConfig(buckConfig));

    CxxSourceRuleFactory cxxSourceRuleFactory =
        new CxxSourceRuleFactory(
            params,
            buildRuleResolver,
            sourcePathResolver,
            platform,
            ImmutableList.<CxxPreprocessorInput>of(),
            explicitCompilerFlags,
            Optional.<SourcePath>absent());

    String cSourceName = "test.i";
    List<String> cSourcePerFileFlags = ImmutableList.of("-c-source-par-file-flag");
    CxxSource cSource = CxxSource.of(
        CxxSource.Type.C_CPP_OUTPUT,
        new FakeSourcePath(cSourceName),
        cSourcePerFileFlags);
    CxxPreprocessAndCompile cCompile =
        cxxSourceRuleFactory.requireCompileBuildRule(
            buildRuleResolver,
            cSourceName,
            cSource,
            CxxSourceRuleFactory.PicType.PDC);
    assertContains(cCompile.getRuleCompilerFlags().get(), explicitCompilerFlags);
    assertContains(cCompile.getPlatformCompilerFlags().get(), cflags);
    assertContains(cCompile.getPlatformCompilerFlags().get(), asflags);
    assertContains(cCompile.getRuleCompilerFlags().get(), cSourcePerFileFlags);

    cSourceName = "test.c";
    cSource = CxxSource.of(
        CxxSource.Type.C,
        new FakeSourcePath(cSourceName),
        cSourcePerFileFlags);
    CxxPreprocessAndCompile cPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            buildRuleResolver,
            cSourceName,
            cSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertContains(cPreprocessAndCompile.getRuleCompilerFlags().get(), explicitCompilerFlags);
    assertContains(cPreprocessAndCompile.getPlatformCompilerFlags().get(), cflags);
    assertContains(cPreprocessAndCompile.getPlatformCompilerFlags().get(), asflags);
    assertContains(cPreprocessAndCompile.getRuleCompilerFlags().get(), cSourcePerFileFlags);

    String cxxSourceName = "test.ii";
    List<String> cxxSourcePerFileFlags = ImmutableList.of("-cxx-source-par-file-flag");
    CxxSource cxxSource =
        CxxSource.of(
            CxxSource.Type.CXX_CPP_OUTPUT,
            new FakeSourcePath(cxxSourceName),
            cxxSourcePerFileFlags);
    CxxPreprocessAndCompile cxxCompile =
        cxxSourceRuleFactory.requireCompileBuildRule(
            buildRuleResolver,
            cxxSourceName,
            cxxSource,
            CxxSourceRuleFactory.PicType.PDC);
    assertContains(cxxCompile.getRuleCompilerFlags().get(), explicitCompilerFlags);
    assertContains(cxxCompile.getPlatformCompilerFlags().get(), cxxflags);
    assertContains(cxxCompile.getPlatformCompilerFlags().get(), asflags);
    assertContains(cxxCompile.getRuleCompilerFlags().get(), cxxSourcePerFileFlags);

    cxxSourceName = "test.cpp";
    cxxSource =
        CxxSource.of(
            CxxSource.Type.CXX,
            new FakeSourcePath(cxxSourceName),
            cxxSourcePerFileFlags);
    CxxPreprocessAndCompile cxxPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            buildRuleResolver,
            cxxSourceName,
            cxxSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertContains(cxxPreprocessAndCompile.getRuleCompilerFlags().get(), explicitCompilerFlags);
    assertContains(cxxPreprocessAndCompile.getPlatformCompilerFlags().get(), cxxflags);
    assertContains(cxxPreprocessAndCompile.getPlatformCompilerFlags().get(), asflags);
    assertContains(cxxPreprocessAndCompile.getRuleCompilerFlags().get(), cxxSourcePerFileFlags);

    String cCppOutputSourceName = "test2.i";
    List<String> cCppOutputSourcePerFileFlags =
        ImmutableList.of("-c-cpp-output-source-par-file-flag");
    CxxSource cCppOutputSource = CxxSource.of(
        CxxSource.Type.C_CPP_OUTPUT,
        new FakeSourcePath(cCppOutputSourceName),
        cCppOutputSourcePerFileFlags);
    CxxPreprocessAndCompile cCppOutputCompile =
        cxxSourceRuleFactory.requireCompileBuildRule(
            buildRuleResolver,
            cCppOutputSourceName,
            cCppOutputSource,
            CxxSourceRuleFactory.PicType.PDC);
    assertContains(cCppOutputCompile.getRuleCompilerFlags().get(), explicitCompilerFlags);
    assertContains(cCppOutputCompile.getPlatformCompilerFlags().get(), cflags);
    assertContains(cCppOutputCompile.getPlatformCompilerFlags().get(), asflags);
    assertContains(cCppOutputCompile.getRuleCompilerFlags().get(), cCppOutputSourcePerFileFlags);

    cCppOutputSourceName = "test2.c";
    cCppOutputSource = CxxSource.of(
        CxxSource.Type.C,
        new FakeSourcePath(cCppOutputSourceName),
        cCppOutputSourcePerFileFlags);
    CxxPreprocessAndCompile cCppOutputPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            buildRuleResolver,
            cCppOutputSourceName,
            cCppOutputSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertContains(
        cCppOutputPreprocessAndCompile.getRuleCompilerFlags().get(),
        explicitCompilerFlags);
    assertContains(cCppOutputPreprocessAndCompile.getPlatformCompilerFlags().get(), cflags);
    assertContains(cCppOutputPreprocessAndCompile.getPlatformCompilerFlags().get(), asflags);
    assertContains(
        cCppOutputPreprocessAndCompile.getRuleCompilerFlags().get(),
        cCppOutputSourcePerFileFlags);

    String assemblerSourceName = "test.s";
    List<String> assemblerSourcePerFileFlags = ImmutableList.of("-assember-source-par-file-flag");
    CxxSource assemblerSource = CxxSource.of(
        CxxSource.Type.ASSEMBLER,
        new FakeSourcePath(assemblerSourceName),
        assemblerSourcePerFileFlags);
    CxxPreprocessAndCompile assemblerCompile =
        cxxSourceRuleFactory.requireCompileBuildRule(
            buildRuleResolver,
            assemblerSourceName,
            assemblerSource,
            CxxSourceRuleFactory.PicType.PDC);
    assertContains(assemblerCompile.getPlatformCompilerFlags().get(), asflags);
    assertContains(assemblerCompile.getRuleCompilerFlags().get(), assemblerSourcePerFileFlags);

    assemblerSourceName = "test.S";
    assemblerSource = CxxSource.of(
        CxxSource.Type.ASSEMBLER_WITH_CPP,
        new FakeSourcePath(assemblerSourceName),
        assemblerSourcePerFileFlags);
    CxxPreprocessAndCompile assemblerPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            buildRuleResolver,
            assemblerSourceName,
            assemblerSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertContains(assemblerPreprocessAndCompile.getPlatformCompilerFlags().get(), asflags);
    assertContains(
        assemblerPreprocessAndCompile.getRuleCompilerFlags().get(),
        assemblerSourcePerFileFlags);
  }

  @Test
  public void languageFlagsArePassed() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();

    String name = "foo/bar.ii";
    SourcePath input = new PathSourcePath(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
    CxxSource cxxSource = CxxSource.of(
        CxxSource.Type.CXX_CPP_OUTPUT,
        input,
        ImmutableList.<String>of());

    CxxSourceRuleFactory cxxSourceRuleFactory =
        new CxxSourceRuleFactory(
            params,
            buildRuleResolver,
            sourcePathResolver,
            CXX_PLATFORM,
            ImmutableList.<CxxPreprocessorInput>of(),
            ImmutableList.<String>of(),
            Optional.<SourcePath>absent());

    CxxPreprocessAndCompile cxxCompile = cxxSourceRuleFactory.createCompileBuildRule(
        buildRuleResolver,
        name,
        cxxSource,
        CxxSourceRuleFactory.PicType.PDC);

    assertThat(cxxCompile.makeMainStep().getCommand(), Matchers.hasItems("-x", "c++-cpp-output"));

    name = "foo/bar.mi";
    input = new PathSourcePath(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
    cxxSource = CxxSource.of(CxxSource.Type.OBJC_CPP_OUTPUT, input, ImmutableList.<String>of());

    cxxCompile = cxxSourceRuleFactory.createCompileBuildRule(
        buildRuleResolver,
        name,
        cxxSource,
        CxxSourceRuleFactory.PicType.PDC);

    assertThat(
        cxxCompile.makeMainStep().getCommand(),
        Matchers.hasItems("-x", "objective-c-cpp-output"));

    name = "foo/bar.mii";
    input = new PathSourcePath(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
    cxxSource = CxxSource.of(CxxSource.Type.OBJCXX_CPP_OUTPUT, input, ImmutableList.<String>of());

    cxxCompile = cxxSourceRuleFactory.createCompileBuildRule(
        buildRuleResolver,
        name,
        cxxSource,
        CxxSourceRuleFactory.PicType.PDC);

    assertThat(
        cxxCompile.makeMainStep().getCommand(),
        Matchers.hasItems("-x", "objective-c++-cpp-output"));

    name = "foo/bar.i";
    input = new PathSourcePath(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
    cxxSource = CxxSource.of(CxxSource.Type.C_CPP_OUTPUT, input, ImmutableList.<String>of());

    cxxCompile = cxxSourceRuleFactory.createCompileBuildRule(
        buildRuleResolver,
        name,
        cxxSource,
        CxxSourceRuleFactory.PicType.PDC);

    assertThat(cxxCompile.makeMainStep().getCommand(), Matchers.hasItems("-x", "cpp-output"));
  }

  @Test
     public void checkCorrectFlagsAreUsedForObjcAndObjcxx() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

    ImmutableList<String> explicitCompilerFlags = ImmutableList.of("-fobjc-arc");

    BuckConfig buckConfig = FakeBuckConfig.builder().setFilesystem(filesystem).build();
    CxxPlatform platform = DefaultCxxPlatforms.build(new CxxBuckConfig(buckConfig));

    CxxSourceRuleFactory cxxSourceRuleFactory =
        new CxxSourceRuleFactory(
            params,
            buildRuleResolver,
            new SourcePathResolver(buildRuleResolver),
            platform,
            ImmutableList.<CxxPreprocessorInput>of(),
            explicitCompilerFlags,
            Optional.<SourcePath>absent());

    String objcSourceName = "test.mi";
    CxxSource objcSource = CxxSource.of(
        CxxSource.Type.OBJC_CPP_OUTPUT,
        new FakeSourcePath(objcSourceName),
        ImmutableList.<String>of());
    CxxPreprocessAndCompile objcCompile =
        cxxSourceRuleFactory.requireCompileBuildRule(
            buildRuleResolver,
            objcSourceName,
            objcSource,
            CxxSourceRuleFactory.PicType.PDC);
    assertContains(objcCompile.getRuleCompilerFlags().get(), explicitCompilerFlags);

    objcSourceName = "test.m";
    objcSource = CxxSource.of(
        CxxSource.Type.OBJC,
        new FakeSourcePath(objcSourceName),
        ImmutableList.<String>of());
    CxxPreprocessAndCompile objcPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            buildRuleResolver,
            objcSourceName,
            objcSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertContains(objcPreprocessAndCompile.getRuleCompilerFlags().get(), explicitCompilerFlags);

    String objcxxSourceName = "test.mii";
    CxxSource objcxxSource = CxxSource.of(
        CxxSource.Type.OBJCXX_CPP_OUTPUT,
        new FakeSourcePath(objcxxSourceName),
        ImmutableList.<String>of());
    CxxPreprocessAndCompile objcxxCompile =
        cxxSourceRuleFactory.requireCompileBuildRule(
            buildRuleResolver,
            objcxxSourceName,
            objcxxSource,
            CxxSourceRuleFactory.PicType.PDC);
    assertContains(objcxxCompile.getRuleCompilerFlags().get(), explicitCompilerFlags);

    objcxxSourceName = "test.mm";
    objcxxSource = CxxSource.of(
        CxxSource.Type.OBJCXX,
        new FakeSourcePath(objcxxSourceName),
        ImmutableList.<String>of());
    CxxPreprocessAndCompile objcxxPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            buildRuleResolver,
            objcxxSourceName,
            objcxxSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);
    assertContains(objcxxPreprocessAndCompile.getRuleCompilerFlags().get(), explicitCompilerFlags);
  }

  @Test
  public void checkPrefixHeaderIsIncluded() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

    BuckConfig buckConfig = FakeBuckConfig.builder().setFilesystem(filesystem).build();
    CxxPlatform platform = DefaultCxxPlatforms.build(new CxxBuckConfig(buckConfig));

    String prefixHeaderName = "test.pch";
    SourcePath prefixHeaderSourcePath = new FakeSourcePath(prefixHeaderName);

    CxxSourceRuleFactory cxxSourceRuleFactory =
        new CxxSourceRuleFactory(
            params,
            buildRuleResolver,
            new SourcePathResolver(buildRuleResolver),
            platform,
            ImmutableList.<CxxPreprocessorInput>of(),
            ImmutableList.<String>of(),
            Optional.<SourcePath>of(prefixHeaderSourcePath));

    String objcSourceName = "test.m";
    CxxSource objcSource = CxxSource.of(
        CxxSource.Type.OBJC,
        new FakeSourcePath(objcSourceName),
        ImmutableList.<String>of());
    CxxPreprocessAndCompile objcPreprocessAndCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            buildRuleResolver,
            objcSourceName,
            objcSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);

    ImmutableList<String> explicitPrefixHeaderRelatedFlags = ImmutableList.of(
        "-include", prefixHeaderName);

    CxxPreprocessAndCompileStep step = objcPreprocessAndCompile.makeMainStep();
    assertContains(step.getCommand(), explicitPrefixHeaderRelatedFlags);
  }

  @Test
  public void duplicateRuleFetchedFromResolver() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

    BuckConfig buckConfig = FakeBuckConfig.builder().setFilesystem(filesystem).build();
    CxxPlatform platform = DefaultCxxPlatforms.build(new CxxBuckConfig(buckConfig));

    CxxSourceRuleFactory cxxSourceRuleFactory =
        new CxxSourceRuleFactory(
            params,
            buildRuleResolver,
            new SourcePathResolver(buildRuleResolver),
            platform,
            ImmutableList.<CxxPreprocessorInput>of(),
            ImmutableList.<String>of(),
            Optional.<SourcePath>absent());

    String objcSourceName = "test.m";
    CxxSource objcSource = CxxSource.of(
        CxxSource.Type.OBJC,
        new FakeSourcePath(objcSourceName),
        ImmutableList.<String>of());
    CxxPreprocessAndCompile objcCompile =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            buildRuleResolver,
            objcSourceName,
            objcSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);

    // Make sure we can get the same build rule twice.
    CxxPreprocessAndCompile objcCompile2 =
        cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
            buildRuleResolver,
            objcSourceName,
            objcSource,
            CxxSourceRuleFactory.PicType.PDC,
            CxxPreprocessMode.SEPARATE);

    assertEquals(objcCompile.getBuildTarget(), objcCompile2.getBuildTarget());
  }
}
