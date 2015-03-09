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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class CxxSourceRuleFactoryTest {

  private static final ProjectFilesystem PROJECT_FILESYSTEM = new FakeProjectFilesystem();

  private static final CxxPlatform CXX_PLATFORM = DefaultCxxPlatforms.build(
      new FakeBuckConfig());

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
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build(),
        resolver);
  }

  @Test
  public void createPreprocessBuildRulePropagatesCxxPreprocessorDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
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

    String name = "foo/bar.cpp";
    SourcePath input = new PathSourcePath(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
    CxxSource cxxSource = ImmutableCxxSource.of(
        CxxSource.Type.CXX,
        input,
        ImmutableList.<String>of());

    Map.Entry<String, CxxSource> entry =
        CxxSourceRuleFactory.createPreprocessBuildRule(
            params,
            resolver,
            CXX_PLATFORM,
            cxxPreprocessorInput,
            /* pic */ false,
            name,
            cxxSource);
    BuildRule cxxPreprocess = pathResolver.getRule(entry.getValue().getPath()).get();
    assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxPreprocess.getDeps());
  }

  @Test
  public void preprocessFlagsFromPlatformArePropagated() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    CxxPreprocessorInput cxxPreprocessorInput = CxxPreprocessorInput.EMPTY;

    String name = "source.cpp";
    CxxSource cxxSource = ImmutableCxxSource.of(
        CxxSource.Type.CXX,
        new TestSourcePath(name),
        ImmutableList.<String>of());

    ImmutableList<String> platformFlags = ImmutableList.of("-some", "-flags");
    CxxPlatform platform = DefaultCxxPlatforms.build(
        new FakeBuckConfig(
            ImmutableMap.<String, Map<String, String>>of(
                "cxx", ImmutableMap.of("cxxppflags", Joiner.on(" ").join(platformFlags)))));

    // Verify that platform flags make it to the compile rule.
    Map.Entry<String, CxxSource> output =
        CxxSourceRuleFactory.createPreprocessBuildRule(
            params,
            resolver,
            platform,
            cxxPreprocessorInput,
            /* pic */ false,
            name,
            cxxSource);
    CxxPreprocess cxxPreprocess =
        (CxxPreprocess) pathResolver.getRule(output.getValue().getPath()).get();
    assertNotEquals(
        -1,
        Collections.indexOfSubList(cxxPreprocess.getFlags(), platformFlags));
  }

  @Test
  public void checkCorrectPreprocessFlagsAreUsed() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
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

    SourcePath cpp = new TestSourcePath("cpp");
    ImmutableList<String> cppflags = ImmutableList.of("-cppflag", "-cppflag");

    SourcePath cxxpp = new TestSourcePath("cxxpp");
    ImmutableList<String> cxxppflags = ImmutableList.of("-cxxppflag", "-cxxppflag");

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "cxx", ImmutableMap.<String, String>builder()
                .put("asppflags", space.join(asppflags))
                .put("cpp", sourcePathResolver.getPath(cpp).toString())
                .put("cppflags", space.join(cppflags))
                .put("cxxpp", sourcePathResolver.getPath(cxxpp).toString())
                .put("cxxppflags", space.join(cxxppflags))
                .build()),
        filesystem);
    CxxPlatform platform = DefaultCxxPlatforms.build(buckConfig);

    String cSourceName = "test.c";
    List<String> perFileFlagsForTestC =
        ImmutableList.of("-per-file-flag-for-c-file", "-and-another-one");
    CxxSource cSource = ImmutableCxxSource.of(
        CxxSource.Type.C,
        new TestSourcePath(cSourceName),
        perFileFlagsForTestC);
    Map.Entry<String, CxxSource> cPreprocessEntry =
        CxxSourceRuleFactory.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            platform,
            cxxPreprocessorInput,
            /* pic */ false,
            cSourceName,
            cSource);
    CxxPreprocess cPreprocess =
        (CxxPreprocess) sourcePathResolver.getRule(cPreprocessEntry.getValue().getPath()).get();
    assertContains(cPreprocess.getFlags(), explicitCppflags);
    assertContains(cPreprocess.getFlags(), cppflags);
    assertContains(cPreprocess.getFlags(), perFileFlagsForTestC);

    String cxxSourceName = "test.cpp";
    List<String> perFileFlagsForTestCpp =
        ImmutableList.of("-per-file-flag-for-cpp-file");
    CxxSource cxxSource = ImmutableCxxSource.of(
        CxxSource.Type.CXX,
        new TestSourcePath(cxxSourceName),
        perFileFlagsForTestCpp);
    Map.Entry<String, CxxSource> cxxPreprocessEntry =
        CxxSourceRuleFactory.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            platform,
            cxxPreprocessorInput,
            /* pic */ false,
            cxxSourceName,
            cxxSource);
    CxxPreprocess cxxPreprocess =
        (CxxPreprocess) sourcePathResolver.getRule(cxxPreprocessEntry.getValue().getPath()).get();
    assertContains(cxxPreprocess.getFlags(), explicitCxxppflags);
    assertContains(cxxPreprocess.getFlags(), cxxppflags);
    assertContains(cxxPreprocess.getFlags(), perFileFlagsForTestCpp);

    String assemblerWithCppSourceName = "test.S";
    List<String> perFileFlagsForTestS =
        ImmutableList.of("-a-flag-for-s-file", "-another-one", "-one-more");
    CxxSource assemblerWithCppSource = ImmutableCxxSource.of(
        CxxSource.Type.ASSEMBLER_WITH_CPP,
        new TestSourcePath(assemblerWithCppSourceName),
        perFileFlagsForTestS);
    Map.Entry<String, CxxSource> assemblerWithCppCompileEntry =
        CxxSourceRuleFactory.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            platform,
            cxxPreprocessorInput,
            /* pic */ false,
            assemblerWithCppSourceName,
            assemblerWithCppSource);
    CxxPreprocess assemblerWithCppPreprocess =
        (CxxPreprocess) sourcePathResolver.getRule(
            assemblerWithCppCompileEntry.getValue().getPath()).get();
    assertContains(assemblerWithCppPreprocess.getFlags(), asppflags);
    assertContains(assemblerWithCppPreprocess.getFlags(), perFileFlagsForTestS);
  }

  @Test
  public void languagePreprocessFlagsArePassed() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(buildRuleResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    String name = "foo/bar.cpp";
    SourcePath input = new PathSourcePath(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
    CxxSource cxxSource = ImmutableCxxSource.of(
        CxxSource.Type.CXX,
        input,
        ImmutableList.<String>of());

    Map.Entry<String, CxxSource> cxxPreprocessEntry =
        CxxSourceRuleFactory.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            CXX_PLATFORM,
            CxxPreprocessorInput.EMPTY,
            /* pic */ false,
            name,
            cxxSource);
    CxxPreprocess cxxPreprocess =
        (CxxPreprocess) pathResolver.getRule(
            cxxPreprocessEntry.getValue().getPath()).get();
    assertThat(cxxPreprocess.getFlags(), Matchers.contains("-x", "c++"));

    name = "foo/bar.m";
    input = new PathSourcePath(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
    cxxSource = ImmutableCxxSource.of(
        CxxSource.Type.OBJC,
        input,
        ImmutableList.<String>of());

    cxxPreprocessEntry =
        CxxSourceRuleFactory.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            CXX_PLATFORM,
            CxxPreprocessorInput.EMPTY,
            /* pic */ false,
            name,
            cxxSource);
    cxxPreprocess =
        (CxxPreprocess) pathResolver.getRule(
            cxxPreprocessEntry.getValue().getPath()).get();
    assertThat(cxxPreprocess.getFlags(), Matchers.contains("-x", "objective-c"));

    name = "foo/bar.mm";
    input = new PathSourcePath(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
    cxxSource = ImmutableCxxSource.of(
        CxxSource.Type.OBJCXX,
        input,
        ImmutableList.<String>of());

    cxxPreprocessEntry =
        CxxSourceRuleFactory.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            CXX_PLATFORM,
            CxxPreprocessorInput.EMPTY,
            /* pic */ false,
            name,
            cxxSource);
    cxxPreprocess =
        (CxxPreprocess) pathResolver.getRule(
            cxxPreprocessEntry.getValue().getPath()).get();
    assertThat(cxxPreprocess.getFlags(), Matchers.contains("-x", "objective-c++"));

    name = "foo/bar.c";
    input = new PathSourcePath(PROJECT_FILESYSTEM, target.getBasePath().resolve(name));
    cxxSource = ImmutableCxxSource.of(
        CxxSource.Type.C,
        input,
        ImmutableList.<String>of());

    cxxPreprocessEntry =
        CxxSourceRuleFactory.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            CXX_PLATFORM,
            CxxPreprocessorInput.EMPTY,
            /* pic */ false,
            name,
            cxxSource);
    cxxPreprocess =
        (CxxPreprocess) pathResolver.getRule(
            cxxPreprocessEntry.getValue().getPath()).get();
    assertThat(cxxPreprocess.getFlags(), Matchers.contains("-x", "c"));
  }

  @Test
  public void createCompileBuildRulePropagatesBuildRuleSourcePathDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    BuildRuleResolver resolver = new BuildRuleResolver();

    String name = "foo/bar.ii";
    FakeBuildRule dep = createFakeBuildRule("//:test", new SourcePathResolver(resolver));
    resolver.addToIndex(dep);
    SourcePath input = new BuildTargetSourcePath(PROJECT_FILESYSTEM, dep.getBuildTarget());
    CxxSource cxxSource = ImmutableCxxSource.of(
        CxxSource.Type.CXX_CPP_OUTPUT,
        input,
        ImmutableList.<String>of());

    CxxCompile cxxCompile = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        resolver,
        CXX_PLATFORM,
        ImmutableList.<String>of(),
        /* pic */ false,
        name,
        cxxSource);

    assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxCompile.getDeps());
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void createCompileBuildRulePicOption() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    BuildRuleResolver resolver = new BuildRuleResolver();

    String name = "foo/bar.ii";
    CxxSource cxxSource = ImmutableCxxSource.of(
        CxxSource.Type.CXX_CPP_OUTPUT,
        new TestSourcePath(name),
        ImmutableList.<String>of());

    // Verify building a non-PIC compile rule does *not* have the "-fPIC" flag and has the
    // expected compile target.
    CxxCompile noPic = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        resolver,
        CXX_PLATFORM,
        ImmutableList.<String>of(),
        /* pic */ false,
        name,
        cxxSource);
    assertFalse(noPic.getFlags().contains("-fPIC"));
    assertEquals(
        CxxSourceRuleFactory.createCompileBuildTarget(
            target,
            CXX_PLATFORM.getFlavor(),
            name,
            /* pic */ false),
        noPic.getBuildTarget());

    // Verify building a PIC compile rule *does* have the "-fPIC" flag and has the
    // expected compile target.
    CxxCompile pic = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        resolver,
        CXX_PLATFORM,
        ImmutableList.<String>of(),
        /* pic */ true,
        name,
        cxxSource);
    assertTrue(pic.getFlags().contains("-fPIC"));
    assertEquals(
        CxxSourceRuleFactory.createCompileBuildTarget(
            target,
            CXX_PLATFORM.getFlavor(),
            name,
            /* pic */ true),
        pic.getBuildTarget());
  }

  @Test
  public void compilerFlagsFromPlatformArePropagated() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    BuildRuleResolver resolver = new BuildRuleResolver();

    String name = "source.ii";
    CxxSource cxxSource = ImmutableCxxSource.of(
        CxxSource.Type.CXX_CPP_OUTPUT,
        new TestSourcePath(name),
        ImmutableList.<String>of());

    ImmutableList<String> platformFlags = ImmutableList.of("-some", "-flags");
    CxxPlatform platform = DefaultCxxPlatforms.build(
        new FakeBuckConfig(
            ImmutableMap.<String, Map<String, String>>of(
                "cxx", ImmutableMap.of("cxxflags", Joiner.on(" ").join(platformFlags)))));

    // Verify that platform flags make it to the compile rule.
    CxxCompile cxxCompile = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        resolver,
        platform,
        ImmutableList.<String>of(),
        /* pic */ false,
        name,
        cxxSource);
    assertNotEquals(
        -1,
        Collections.indexOfSubList(cxxCompile.getFlags(), platformFlags));
  }

  @Test
  public void checkCorrectCompilerFlagsAreUsed() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    Joiner space = Joiner.on(" ");

    ImmutableList<String> explicitCompilerFlags = ImmutableList.of("-explicit-compilerflag");

    SourcePath as = new TestSourcePath("as");
    ImmutableList<String> asflags = ImmutableList.of("-asflag", "-asflag");

    SourcePath cc = new TestSourcePath("cc");
    ImmutableList<String> cflags = ImmutableList.of("-cflag", "-cflag");

    SourcePath cxx = new TestSourcePath("cxx");
    ImmutableList<String> cxxflags = ImmutableList.of("-cxxflag", "-cxxflag");

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "cxx", ImmutableMap.<String, String>builder()
                .put("as", sourcePathResolver.getPath(as).toString())
                .put("asflags", space.join(asflags))
                .put("cc", sourcePathResolver.getPath(cc).toString())
                .put("cflags", space.join(cflags))
                .put("cxx", sourcePathResolver.getPath(cxx).toString())
                .put("cxxflags", space.join(cxxflags))
                .build()),
        filesystem);
    CxxPlatform platform = DefaultCxxPlatforms.build(buckConfig);

    String cSourceName = "test.i";
    List<String> cSourcePerFileFlags = ImmutableList.of("-c-source-par-file-flag");
    CxxSource cSource = ImmutableCxxSource.of(
        CxxSource.Type.C_CPP_OUTPUT,
        new TestSourcePath(cSourceName),
        cSourcePerFileFlags);
    CxxCompile cCompile = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        explicitCompilerFlags,
        /* pic */ false,
        cSourceName,
        cSource);
    assertContains(cCompile.getFlags(), explicitCompilerFlags);
    assertContains(cCompile.getFlags(), cflags);
    assertContains(cCompile.getFlags(), asflags);
    assertContains(cCompile.getFlags(), cSourcePerFileFlags);

    String cxxSourceName = "test.ii";
    List<String> cxxSourcePerFileFlags = ImmutableList.of("-cxx-source-par-file-flag");
    CxxSource cxxSource =
        ImmutableCxxSource.of(
            CxxSource.Type.CXX_CPP_OUTPUT,
            new TestSourcePath(cxxSourceName),
            cxxSourcePerFileFlags);
    CxxCompile cxxCompile = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        explicitCompilerFlags,
        /* pic */ false,
        cxxSourceName,
        cxxSource);
    assertContains(cxxCompile.getFlags(), explicitCompilerFlags);
    assertContains(cxxCompile.getFlags(), cxxflags);
    assertContains(cxxCompile.getFlags(), asflags);
    assertContains(cxxCompile.getFlags(), cxxSourcePerFileFlags);

    String cCppOutputSourceName = "test.i";
    List<String> cCppOutputSourcePerFileFlags =
        ImmutableList.of("-c-cpp-output-source-par-file-flag");
    CxxSource cCppOutputSource = ImmutableCxxSource.of(
        CxxSource.Type.C_CPP_OUTPUT,
        new TestSourcePath(cCppOutputSourceName),
        cCppOutputSourcePerFileFlags);
    CxxCompile cCppOutputCompile = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        explicitCompilerFlags,
        /* pic */ false,
        cCppOutputSourceName,
        cCppOutputSource);
    assertContains(cCppOutputCompile.getFlags(), explicitCompilerFlags);
    assertContains(cCppOutputCompile.getFlags(), cflags);
    assertContains(cCppOutputCompile.getFlags(), asflags);
    assertContains(cCppOutputCompile.getFlags(), cCppOutputSourcePerFileFlags);

    String assemblerSourceName = "test.s";
    List<String> assemblerSourcePerFileFlags = ImmutableList.of("-assember-source-par-file-flag");
    CxxSource assemblerSource = ImmutableCxxSource.of(
        CxxSource.Type.ASSEMBLER,
        new TestSourcePath(assemblerSourceName),
        assemblerSourcePerFileFlags);
    CxxCompile assemblerCompile = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        explicitCompilerFlags,
        /* pic */ false,
        assemblerSourceName,
        assemblerSource);
    assertContains(assemblerCompile.getFlags(), asflags);
    assertContains(assemblerCompile.getFlags(), assemblerSourcePerFileFlags);
  }

  // TODO(#5393669): Re-enable once we can handle the language flag in a portable way.
  /*@Test
  public void languageCompilerFlagsArePassed() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    String name = "foo/bar.ii";
    SourcePath input = new PathSourcePath(target.getBasePath().resolve(name));
    CxxSource cxxSource = new CxxSource(CxxSource.Type.CXX_CPP_OUTPUT, input);

    CxxCompile cxxCompile = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        buildRuleResolver,
        CXX_PLATFORM,
        ImmutableList.<String>of(),
        false,
        name,
        cxxSource);

    assertThat(cxxCompile.getFlags(), Matchers.contains("-x", "c++-cpp-output"));

    name = "foo/bar.mi";
    input = new PathSourcePath(target.getBasePath().resolve(name));
    cxxSource = new CxxSource(CxxSource.Type.OBJC_CPP_OUTPUT, input);

    cxxCompile = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        buildRuleResolver,
        CXX_PLATFORM,
        ImmutableList.<String>of(),
        false,
        name,
        cxxSource);

    assertThat(cxxCompile.getFlags(), Matchers.contains("-x", "objective-c-cpp-output"));

    name = "foo/bar.mii";
    input = new PathSourcePath(target.getBasePath().resolve(name));
    cxxSource = new CxxSource(CxxSource.Type.OBJCXX_CPP_OUTPUT, input);

    cxxCompile = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        buildRuleResolver,
        CXX_PLATFORM,
        ImmutableList.<String>of(),
        false,
        name,
        cxxSource);

    assertThat(cxxCompile.getFlags(), Matchers.contains("-x", "objective-c++-cpp-output"));

    name = "foo/bar.i";
    input = new PathSourcePath(target.getBasePath().resolve(name));
    cxxSource = new CxxSource(CxxSource.Type.C_CPP_OUTPUT, input);

    cxxCompile = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        buildRuleResolver,
        CXX_PLATFORM,
        ImmutableList.<String>of(),
        false,
        name,
        cxxSource);

    assertThat(cxxCompile.getFlags(), Matchers.contains("-x", "c-cpp-output"));
  }*/

  @Test
  public void checkCorrectCompilerFlagsAreUsedForObjcAndObjcxx() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();

    ImmutableList<String> explicitCompilerFlags = ImmutableList.of("-fobjc-arc");

    FakeBuckConfig buckConfig = new FakeBuckConfig(filesystem);
    CxxPlatform platform = DefaultCxxPlatforms.build(buckConfig);

    String objcSourceName = "test.mi";
    CxxSource objcSource = ImmutableCxxSource.of(
        CxxSource.Type.OBJC_CPP_OUTPUT,
        new TestSourcePath(objcSourceName),
        ImmutableList.<String>of());
    CxxCompile objcCompile = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        explicitCompilerFlags,
        /* pic */ false,
        objcSourceName,
        objcSource);
    assertContains(objcCompile.getFlags(), explicitCompilerFlags);

    String objcxxSourceName = "test.mii";
    CxxSource objcxxSource = ImmutableCxxSource.of(
        CxxSource.Type.OBJCXX_CPP_OUTPUT,
        new TestSourcePath(objcxxSourceName),
        ImmutableList.<String>of());
    CxxCompile objcxxCompile = CxxSourceRuleFactory.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        explicitCompilerFlags,
        /* pic */ false,
        objcxxSourceName,
        objcxxSource);
    assertContains(objcxxCompile.getFlags(), explicitCompilerFlags);
  }

}
