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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

public class CxxCompilableEnhancerTest {

  private static final CxxPlatform CXX_PLATFORM = DefaultCxxPlatforms.build(new FakeBuckConfig());

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
  public void createCompileBuildRulePropagatesBuildRuleSourcePathDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    BuildRuleResolver resolver = new BuildRuleResolver();

    String name = "foo/bar.ii";
    FakeBuildRule dep = createFakeBuildRule("//:test", new SourcePathResolver(resolver));
    resolver.addToIndex(dep);
    SourcePath input = new BuildTargetSourcePath(dep.getBuildTarget());
    CxxSource cxxSource = ImmutableCxxSource.of(CxxSource.Type.CXX_CPP_OUTPUT, input);

    CxxCompile cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
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
        new TestSourcePath(name));

    // Verify building a non-PIC compile rule does *not* have the "-fPIC" flag and has the
    // expected compile target.
    CxxCompile noPic = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        resolver,
        CXX_PLATFORM,
        ImmutableList.<String>of(),
        /* pic */ false,
        name,
        cxxSource);
    assertFalse(noPic.getFlags().contains("-fPIC"));
    assertEquals(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            CXX_PLATFORM.getFlavor(),
            name,
            /* pic */ false),
        noPic.getBuildTarget());

    // Verify building a PIC compile rule *does* have the "-fPIC" flag and has the
    // expected compile target.
    CxxCompile pic = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        resolver,
        CXX_PLATFORM,
        ImmutableList.<String>of(),
        /* pic */ true,
        name,
        cxxSource);
    assertTrue(pic.getFlags().contains("-fPIC"));
    assertEquals(
        CxxCompilableEnhancer.createCompileBuildTarget(
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
        new TestSourcePath(name));

    ImmutableList<String> platformFlags = ImmutableList.of("-some", "-flags");
    CxxPlatform platform = DefaultCxxPlatforms.build(
        new FakeBuckConfig(
            ImmutableMap.<String, Map<String, String>>of(
                "cxx", ImmutableMap.of("cxxflags", Joiner.on(" ").join(platformFlags)))));

    // Verify that platform flags make it to the compile rule.
    CxxCompile cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
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
  public void checkCorrectFlagsAreUsed() {
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
    CxxSource cSource = ImmutableCxxSource.of(
        CxxSource.Type.C_CPP_OUTPUT,
        new TestSourcePath(cSourceName));
    CxxCompile cCompile = CxxCompilableEnhancer.createCompileBuildRule(
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

    String cxxSourceName = "test.ii";
    CxxSource cxxSource =
        ImmutableCxxSource.of(CxxSource.Type.CXX_CPP_OUTPUT, new TestSourcePath(cxxSourceName));
    CxxCompile cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
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

    String cCppOutputSourceName = "test.i";
    CxxSource cCppOutputSource = ImmutableCxxSource.of(
        CxxSource.Type.C_CPP_OUTPUT,
        new TestSourcePath(cCppOutputSourceName));
    CxxCompile cCppOutputCompile = CxxCompilableEnhancer.createCompileBuildRule(
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

    String assemblerSourceName = "test.s";
    CxxSource assemblerSource = ImmutableCxxSource.of(
        CxxSource.Type.ASSEMBLER,
        new TestSourcePath(assemblerSourceName));
    CxxCompile assemblerCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        buildRuleResolver,
        platform,
        explicitCompilerFlags,
        /* pic */ false,
        assemblerSourceName,
        assemblerSource);
    assertContains(assemblerCompile.getFlags(), asflags);
  }

  // TODO(#5393669): Re-enable once we can handle the language flag in a portable way.
  /*@Test
  public void languageFlagsArePassed() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    String name = "foo/bar.ii";
    SourcePath input = new PathSourcePath(target.getBasePath().resolve(name));
    CxxSource cxxSource = new CxxSource(CxxSource.Type.CXX_CPP_OUTPUT, input);

    CxxCompile cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
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

    cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
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

    cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
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

    cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        buildRuleResolver,
        CXX_PLATFORM,
        ImmutableList.<String>of(),
        false,
        name,
        cxxSource);

    assertThat(cxxCompile.getFlags(), Matchers.contains("-x", "c-cpp-output"));
  }*/

}
