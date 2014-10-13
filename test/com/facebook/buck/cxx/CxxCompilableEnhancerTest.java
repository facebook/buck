/*
 * Copyright 2014-present Facebook, Inc.
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
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

public class CxxCompilableEnhancerTest {

  private static final CxxPlatform CXX_PLATFORM = new DefaultCxxPlatform(new FakeBuckConfig());

  private static <T> void assertContains(ImmutableList<T> container, Iterable<T> items) {
    for (T item : items) {
      assertThat(container, Matchers.hasItem(item));
    }
  }

  private static <T> void assertNotContains(ImmutableList<T> container, Iterable<T> items) {
    for (T item : items) {
      assertThat(container, Matchers.not(Matchers.hasItem(item)));
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
  public void createCompileBuildRulePropagatesCxxPreprocessorDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    BuildRuleResolver resolver = new BuildRuleResolver();

    FakeBuildRule dep = resolver.addToIndex(createFakeBuildRule(
            "//:dep1",
            new SourcePathResolver(new BuildRuleResolver())));

    CxxPreprocessorInput cxxPreprocessorInput = CxxPreprocessorInput.builder()
        .setRules(ImmutableSet.of(dep.getBuildTarget()))
        .build();

    String name = "foo/bar.cpp";
    SourcePath input = new PathSourcePath(target.getBasePath().resolve(name));
    CxxSource cxxSource = new CxxSource(CxxSource.Type.CXX, input);

    CxxCompile cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        resolver,
        CXX_PLATFORM,
        cxxPreprocessorInput,
        ImmutableList.<String>of(),
        /* pic */ false,
        name,
        cxxSource);

    assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxCompile.getDeps());
  }

  @Test
  public void createCompileBuildRulePropagatesBuildTargetSourcePathDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    BuildRuleResolver resolver = new BuildRuleResolver();

    String name = "foo/bar.cpp";
    FakeBuildRule dep = createFakeBuildRule("//:test", new SourcePathResolver(resolver));
    resolver.addToIndex(dep);
    SourcePath input = new BuildTargetSourcePath(dep.getBuildTarget());
    CxxSource cxxSource = new CxxSource(CxxSource.Type.CXX, input);

    CxxCompile cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        resolver,
        CXX_PLATFORM,
        CxxPreprocessorInput.EMPTY,
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

    String name = "foo/bar.cpp";
    CxxSource cxxSource = new CxxSource(CxxSource.Type.CXX, new TestSourcePath(name));

    // Verify building a non-PIC compile rule does *not* have the "-fPIC" flag and has the
    // expected compile target.
    CxxCompile noPic = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        resolver,
        CXX_PLATFORM,
        CxxPreprocessorInput.EMPTY,
        ImmutableList.<String>of(),
        /* pic */ false,
        name,
        cxxSource);
    assertFalse(noPic.getFlags().contains("-fPIC"));
    assertEquals(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            name,
            /* pic */ false),
        noPic.getBuildTarget());

    // Verify building a PIC compile rule *does* have the "-fPIC" flag and has the
    // expected compile target.
    CxxCompile pic = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        resolver,
        CXX_PLATFORM,
        CxxPreprocessorInput.EMPTY,
        ImmutableList.<String>of(),
        /* pic */ true,
        name,
        cxxSource);
    assertTrue(pic.getFlags().contains("-fPIC"));
    assertEquals(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            name,
            /* pic */ true),
        pic.getBuildTarget());
  }

  @Test
  public void compilerFlagsFromPlatformArePropagated() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    BuildRuleResolver resolver = new BuildRuleResolver();

    String name = "source.cpp";
    CxxSource cxxSource = new CxxSource(CxxSource.Type.CXX, new TestSourcePath(name));

    ImmutableList<String> platformFlags = ImmutableList.of("-some", "-flags");
    CxxPlatform platform = new DefaultCxxPlatform(
        new FakeBuckConfig(
            ImmutableMap.<String, Map<String, String>>of(
                "cxx", ImmutableMap.of("cxxflags", Joiner.on(" ").join(platformFlags)))));

    // Verify that platform flags make it to the compile rule.
    CxxCompile cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        resolver,
        platform,
        CxxPreprocessorInput.EMPTY,
        ImmutableList.<String>of(),
        /* pic */ false,
        name,
        cxxSource);
    assertNotEquals(
        -1,
        Collections.indexOfSubList(cxxCompile.getFlags(), platformFlags));
  }

  @Test
  public void checkCorrectFlagsAreUsed() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    Joiner space = Joiner.on(" ");

    ImmutableList<String> explicitCompilerFlags = ImmutableList.of("-explicit-compilerflag");

    CxxPreprocessorInput cxxPreprocessorInput = CxxPreprocessorInput.builder()
        .setPreprocessorFlags(
            ImmutableMultimap.of(
                CxxSource.Type.C, "-explicit-cppflag",
                CxxSource.Type.CXX, "-explicit-cxxppflag"))
        .build();

    SourcePath as = new TestSourcePath("as");
    ImmutableList<String> asflags = ImmutableList.of("-asflag", "-asflag");

    ImmutableList<String> asppflags = ImmutableList.of("-asppflag", "-asppflag");

    SourcePath cc = new TestSourcePath("cc");
    ImmutableList<String> cflags = ImmutableList.of("-cflag", "-cflag");

    SourcePath cxx = new TestSourcePath("cxx");
    ImmutableList<String> cxxflags = ImmutableList.of("-cxxflag", "-cxxflag");

    SourcePath cpp = new TestSourcePath("cpp");
    ImmutableList<String> cppflags = ImmutableList.of("-cppflag", "-cppflag");

    SourcePath cxxpp = new TestSourcePath("cxxpp");
    ImmutableList<String> cxxppflags = ImmutableList.of("-cxxppflag", "-cxxppflag");

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "cxx", ImmutableMap.<String, String>builder()
                .put("as", sourcePathResolver.getPath(as).toString())
                .put("asflags", space.join(asflags))
                .put("asppflags", space.join(asppflags))
                .put("cc", sourcePathResolver.getPath(cc).toString())
                .put("cflags", space.join(cflags))
                .put("cxx", sourcePathResolver.getPath(cxx).toString())
                .put("cxxflags", space.join(cxxflags))
                .put("cpp", sourcePathResolver.getPath(cpp).toString())
                .put("cppflags", space.join(cppflags))
                .put("cxxpp", sourcePathResolver.getPath(cxxpp).toString())
                .put("cxxppflags", space.join(cxxppflags))
                .build()),
        filesystem);
    DefaultCxxPlatform platform = new DefaultCxxPlatform(buckConfig);

    String cSourceName = "test.c";
    CxxSource cSource = new CxxSource(CxxSource.Type.C, new TestSourcePath(cSourceName));
    CxxCompile cCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        cxxPreprocessorInput,
        explicitCompilerFlags,
        /* pic */ false,
        cSourceName,
        cSource);
    ImmutableList<String> explicitCppflags = ImmutableList.of("-explicit-cppflag");
    assertContains(cCompile.getFlags(), explicitCppflags);
    assertContains(cCompile.getFlags(), cppflags);
    assertContains(cCompile.getFlags(), explicitCompilerFlags);
    assertContains(cCompile.getFlags(), cflags);
    assertContains(cCompile.getFlags(), asflags);

    String cxxSourceName = "test.cpp";
    CxxSource cxxSource = new CxxSource(CxxSource.Type.CXX, new TestSourcePath(cxxSourceName));
    CxxCompile cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        cxxPreprocessorInput,
        explicitCompilerFlags,
        /* pic */ false,
        cxxSourceName,
        cxxSource);
    ImmutableList<String> explicitCxxppflags = ImmutableList.of("-explicit-cxxppflag");
    assertContains(cxxCompile.getFlags(), explicitCxxppflags);
    assertContains(cxxCompile.getFlags(), cxxppflags);
    assertContains(cxxCompile.getFlags(), explicitCompilerFlags);
    assertContains(cxxCompile.getFlags(), cxxflags);
    assertContains(cxxCompile.getFlags(), asflags);

    String cCppOutputSourceName = "test.i";
    CxxSource cCppOutputSource = new CxxSource(
        CxxSource.Type.C_CPP_OUTPUT,
        new TestSourcePath(cCppOutputSourceName));
    CxxCompile cCppOutputCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        cxxPreprocessorInput,
        explicitCompilerFlags,
        /* pic */ false,
        cCppOutputSourceName,
        cCppOutputSource);
    assertNotContains(cCppOutputCompile.getFlags(), explicitCppflags);
    assertNotContains(cCppOutputCompile.getFlags(), cppflags);
    assertContains(cCppOutputCompile.getFlags(), explicitCompilerFlags);
    assertContains(cCppOutputCompile.getFlags(), cflags);
    assertContains(cCppOutputCompile.getFlags(), asflags);

    String cxxCppOutputSourceName = "test.ii";
    CxxSource cxxCppOutputSource = new CxxSource(
        CxxSource.Type.CXX_CPP_OUTPUT,
        new TestSourcePath(cxxCppOutputSourceName));
    CxxCompile cxxCppOutputCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        cxxPreprocessorInput,
        explicitCompilerFlags,
        /* pic */ false,
        cxxCppOutputSourceName,
        cxxCppOutputSource);
    assertNotContains(cxxCppOutputCompile.getFlags(), explicitCxxppflags);
    assertNotContains(cxxCppOutputCompile.getFlags(), cxxppflags);
    assertContains(cxxCppOutputCompile.getFlags(), explicitCompilerFlags);
    assertContains(cxxCppOutputCompile.getFlags(), cxxflags);
    assertContains(cxxCppOutputCompile.getFlags(), asflags);

    String assemblerSourceName = "test.s";
    CxxSource assemblerSource = new CxxSource(
        CxxSource.Type.ASSEMBLER,
        new TestSourcePath(assemblerSourceName));
    CxxCompile assemblerCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        cxxPreprocessorInput,
        explicitCompilerFlags,
        /* pic */ false,
        assemblerSourceName,
        assemblerSource);
    assertNotContains(assemblerCompile.getFlags(), asppflags);
    assertContains(assemblerCompile.getFlags(), asflags);

    String assemblerWithCppSourceName = "test.S";
    CxxSource assemblerWithCppSource = new CxxSource(
        CxxSource.Type.ASSEMBLER_WITH_CPP,
        new TestSourcePath(assemblerWithCppSourceName));
    CxxCompile assemblerWithCppCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        cxxPreprocessorInput,
        explicitCompilerFlags,
        /* pic */ false,
        assemblerWithCppSourceName,
        assemblerWithCppSource);
    assertContains(assemblerWithCppCompile.getFlags(), asppflags);
    assertContains(assemblerWithCppCompile.getFlags(), asflags);
  }

  @Test
  public void languageFlagsArePassed() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    String name = "foo/bar.cpp";
    SourcePath input = new PathSourcePath(target.getBasePath().resolve(name));
    CxxSource cxxSource = new CxxSource(CxxSource.Type.CXX, input);

    CxxCompile cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        buildRuleResolver,
        CXX_PLATFORM,
        CxxPreprocessorInput.EMPTY,
        ImmutableList.<String>of(),
        /* pic */ false,
        name,
        cxxSource);

    assertThat(cxxCompile.getFlags(), Matchers.contains("-x", "c++"));

    name = "foo/bar.m";
    input = new PathSourcePath(target.getBasePath().resolve(name));
    cxxSource = new CxxSource(CxxSource.Type.OBJC, input);

    cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        buildRuleResolver,
        CXX_PLATFORM,
        CxxPreprocessorInput.EMPTY,
        ImmutableList.<String>of(),
        /* pic */ false,
        name,
        cxxSource);

    assertThat(cxxCompile.getFlags(), Matchers.contains("-x", "objective-c"));

    name = "foo/bar.mm";
    input = new PathSourcePath(target.getBasePath().resolve(name));
    cxxSource = new CxxSource(CxxSource.Type.OBJCXX, input);

    cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        buildRuleResolver,
        CXX_PLATFORM,
        CxxPreprocessorInput.EMPTY,
        ImmutableList.<String>of(),
        /* pic */ false,
        name,
        cxxSource);

    assertThat(cxxCompile.getFlags(), Matchers.contains("-x", "objective-c++"));

    name = "foo/bar.c";
    input = new PathSourcePath(target.getBasePath().resolve(name));
    cxxSource = new CxxSource(CxxSource.Type.C, input);

    cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        buildRuleResolver,
        CXX_PLATFORM,
        CxxPreprocessorInput.EMPTY,
        ImmutableList.<String>of(),
        /* pic */ false,
        name,
        cxxSource);

    assertThat(cxxCompile.getFlags(), Matchers.contains("-x", "c"));
  }
}
