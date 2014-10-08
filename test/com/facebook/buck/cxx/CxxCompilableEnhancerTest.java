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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

public class CxxCompilableEnhancerTest {

  private static final CxxPlatform CXX_PLATFORM = new DefaultCxxPlatform(new FakeBuckConfig());

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

    CxxPreprocessorInput cxxPreprocessorInput = new CxxPreprocessorInput(
        ImmutableSet.of(dep.getBuildTarget()),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of());

    String name = "foo/bar.cpp";
    SourcePath input = new PathSourcePath(target.getBasePath().resolve(name));
    CxxSource cxxSource = new CxxSource(name, input);

    CxxCompile cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        resolver,
        CXX_PLATFORM,
        cxxPreprocessorInput,
        ImmutableList.<String>of(),
        /* pic */ false,
        cxxSource);

    assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxCompile.getDeps());
  }

  @Test
  public void createCompileBuildRulePropagatesBuildRuleSourcePathDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    BuildRuleResolver resolver = new BuildRuleResolver();

    CxxPreprocessorInput cxxPreprocessorInput = new CxxPreprocessorInput(
        ImmutableSet.<BuildTarget>of(),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of());

    String name = "foo/bar.cpp";
    FakeBuildRule dep = createFakeBuildRule("//:test", new SourcePathResolver(resolver));
    SourcePath input = new BuildRuleSourcePath(dep);
    CxxSource cxxSource = new CxxSource(name, input);

    CxxCompile cxxCompile = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        resolver,
        CXX_PLATFORM,
        cxxPreprocessorInput,
        ImmutableList.<String>of(),
        /* pic */ false,
        cxxSource);

    assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxCompile.getDeps());
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void createCompileBuildRulePicOption() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    BuildRuleResolver resolver = new BuildRuleResolver();

    CxxPreprocessorInput cxxPreprocessorInput = new CxxPreprocessorInput(
        ImmutableSet.<BuildTarget>of(),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of());

    String name = "foo/bar.cpp";
    CxxSource cxxSource = new CxxSource(name, new TestSourcePath(name));

    // Verify building a non-PIC compile rule does *not* have the "-fPIC" flag and has the
    // expected compile target.
    CxxCompile noPic = CxxCompilableEnhancer.createCompileBuildRule(
        params,
        resolver,
        CXX_PLATFORM,
        cxxPreprocessorInput,
        ImmutableList.<String>of(),
        /* pic */ false,
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
        cxxPreprocessorInput,
        ImmutableList.<String>of(),
        /* pic */ true,
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

    CxxPreprocessorInput cxxPreprocessorInput = new CxxPreprocessorInput(
        ImmutableSet.<BuildTarget>of(),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of());

    CxxSource cxxSource = new CxxSource("source.cpp", new TestSourcePath("source.cpp"));

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
        cxxPreprocessorInput,
        ImmutableList.<String>of(),
        /* pic */ false,
        cxxSource);
    assertNotEquals(
        -1,
        Collections.indexOfSubList(cxxCompile.getFlags(), platformFlags));
  }

}
