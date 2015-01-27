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

package com.facebook.buck.thrift;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DefaultCxxPlatforms;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ThriftCxxEnhancerTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:test#cpp");
  private static final FakeBuckConfig BUCK_CONFIG = new FakeBuckConfig();
  private static final ThriftBuckConfig THRIFT_BUCK_CONFIG = new ThriftBuckConfig(BUCK_CONFIG);
  private static final CxxBuckConfig CXX_BUCK_CONFIG = new CxxBuckConfig(BUCK_CONFIG);
  private static final CxxPlatform CXX_PLATFORM =
      DefaultCxxPlatforms.build(BUCK_CONFIG);
  private static final FlavorDomain<CxxPlatform> CXX_PLATFORMS =
      new FlavorDomain<>(
          "C/C++ Platform",
          ImmutableMap.<Flavor, CxxPlatform>of(CXX_PLATFORM.getFlavor(), CXX_PLATFORM));
  private static final ThriftCxxEnhancer ENHANCER_CPP = new ThriftCxxEnhancer(
      THRIFT_BUCK_CONFIG,
      CXX_BUCK_CONFIG,
      CXX_PLATFORMS,
      /* cpp2 */ false);
  private static final ThriftCxxEnhancer ENHANCER_CPP2 = new ThriftCxxEnhancer(
      THRIFT_BUCK_CONFIG,
      CXX_BUCK_CONFIG,
      CXX_PLATFORMS,
      /* cpp2 */ true);

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

  private static ThriftCompiler createFakeThriftCompiler(
      String target,
      SourcePathResolver resolver) {
    return new ThriftCompiler(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(
            BuildTargetFactory.newInstance(target)),
        resolver,
        new TestSourcePath("compiler"),
        ImmutableList.<String>of(),
        Paths.get("output"),
        new TestSourcePath("source"),
        "language",
        ImmutableSet.<String>of(),
        ImmutableList.<Path>of(),
        ImmutableMap.<Path, SourcePath>of());
  }

  @Test
  public void getLanguage() {
    assertEquals(
        "cpp",
        ENHANCER_CPP.getLanguage());
    assertEquals(
        "cpp2",
        ENHANCER_CPP2.getLanguage());
  }

  @Test
  public void getFlavor() {
    assertEquals(
        ImmutableFlavor.of("cpp"),
        ENHANCER_CPP.getFlavor());
    assertEquals(
        ImmutableFlavor.of("cpp2"),
        ENHANCER_CPP2.getFlavor());
  }

  private ImmutableSet<String> getExpectedOptions(BuildTarget target, ImmutableSet<String> opts) {
    return ImmutableSet.<String>builder()
        .addAll(opts)
        .add(String.format("include_prefix=%s", target.getBasePath()))
        .build();
  }

  @Test
  public void getOptions() {
    ThriftConstructorArg arg = new ThriftConstructorArg();
    ImmutableSet<String> options;

    // Test empty options.
    options = ImmutableSet.of();
    arg.cppOptions = Optional.of(options);
    assertEquals(
        getExpectedOptions(TARGET, options),
        ENHANCER_CPP.getOptions(TARGET, arg));
    arg.cpp2Options = Optional.of(options);
    assertEquals(
        getExpectedOptions(TARGET, options),
        ENHANCER_CPP2.getOptions(TARGET, arg));

    // Test set options.
    options = ImmutableSet.of("test", "option");
    arg.cppOptions = Optional.of(options);
    assertEquals(
        getExpectedOptions(TARGET, options),
        ENHANCER_CPP.getOptions(TARGET, arg));
    arg.cpp2Options = Optional.of(options);
    assertEquals(
        getExpectedOptions(TARGET, options),
        ENHANCER_CPP.getOptions(TARGET, arg));

    // Test absent options.
    arg.cppOptions = Optional.absent();
    assertEquals(
        getExpectedOptions(TARGET, ImmutableSet.<String>of()),
        ENHANCER_CPP.getOptions(TARGET, arg));
    arg.cpp2Options = Optional.absent();
    assertEquals(
        getExpectedOptions(TARGET, ImmutableSet.<String>of()),
        ENHANCER_CPP2.getOptions(TARGET, arg));
  }

  private void expectImplicitDeps(
      ThriftCxxEnhancer enhancer,
      ImmutableSet<String> options,
      ImmutableSet<BuildTarget> expected) {

    ThriftConstructorArg arg = new ThriftConstructorArg();
    arg.cppOptions = Optional.of(options);
    arg.cpp2Options = Optional.of(options);

    assertEquals(
        expected,
        enhancer.getImplicitDepsForTargetFromConstructorArg(TARGET, arg));
  }

  @Test
  public void getImplicitDeps() {
    // Setup an enhancer which sets all appropriate values in the config.
    ImmutableMap<String, BuildTarget> config = ImmutableMap.of(
        "cpp_library", BuildTargetFactory.newInstance("//:cpp_library"),
        "cpp2_library", BuildTargetFactory.newInstance("//:cpp2_library"),
        "cpp_reflection_library", BuildTargetFactory.newInstance("//:cpp_reflection_library"),
        "cpp_frozen_library", BuildTargetFactory.newInstance("//:cpp_froze_library"),
        "cpp_json_library", BuildTargetFactory.newInstance("//:cpp_json_library"));
    ImmutableMap.Builder<String, String> strConfig = ImmutableMap.builder();
    for (ImmutableMap.Entry<String, BuildTarget> ent : config.entrySet()) {
      strConfig.put(ent.getKey(), ent.getValue().toString());
    }
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of("thrift", strConfig.build()));
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);
    ThriftCxxEnhancer cppEnhancerWithSettings = new ThriftCxxEnhancer(
        thriftBuckConfig,
        CXX_BUCK_CONFIG,
        CXX_PLATFORMS,
        /* cpp2 */ false);
    ThriftCxxEnhancer cpp2EnhancerWithSettings = new ThriftCxxEnhancer(
        thriftBuckConfig,
        CXX_BUCK_CONFIG,
        CXX_PLATFORMS,
        /* cpp2 */ true);

    // With no options we just need to find the C/C++ thrift library.
    expectImplicitDeps(
        cppEnhancerWithSettings,
        ImmutableSet.<String>of(),
        ImmutableSet.of(
            config.get("cpp_library"),
            config.get("cpp_reflection_library")));
    expectImplicitDeps(
        cpp2EnhancerWithSettings,
        ImmutableSet.<String>of(),
        ImmutableSet.of(
            config.get("cpp2_library"),
            config.get("cpp_reflection_library")));

    // Now check for correct reaction to the "bootstrap" option.
    expectImplicitDeps(
        cppEnhancerWithSettings,
        ImmutableSet.of("bootstrap"),
        ImmutableSet.<BuildTarget>of());
    expectImplicitDeps(
        cpp2EnhancerWithSettings,
        ImmutableSet.of("bootstrap"),
        ImmutableSet.<BuildTarget>of());

    // Check the "frozen2" option
    expectImplicitDeps(
        cppEnhancerWithSettings,
        ImmutableSet.of("frozen2"),
        ImmutableSet.of(
            config.get("cpp_library"),
            config.get("cpp_reflection_library"),
            config.get("cpp_frozen_library")));
    expectImplicitDeps(
        cpp2EnhancerWithSettings,
        ImmutableSet.of("frozen2"),
        ImmutableSet.of(
            config.get("cpp2_library"),
            config.get("cpp_reflection_library"),
            config.get("cpp_frozen_library")));

    // Check the "json" option
    expectImplicitDeps(
        cppEnhancerWithSettings,
        ImmutableSet.of("json"),
        ImmutableSet.of(
            config.get("cpp_library"),
            config.get("cpp_reflection_library"),
            config.get("cpp_json_library")));
    expectImplicitDeps(
        cpp2EnhancerWithSettings,
        ImmutableSet.of("json"),
        ImmutableSet.of(
            config.get("cpp2_library"),
            config.get("cpp_reflection_library"),
            config.get("cpp_json_library")));

    // Check the "compatibility" option
    expectImplicitDeps(
        cppEnhancerWithSettings,
        ImmutableSet.of("compatibility"),
        ImmutableSet.of(
            config.get("cpp_library"),
            config.get("cpp_reflection_library")));
    expectImplicitDeps(
        cpp2EnhancerWithSettings,
        ImmutableSet.of("compatibility"),
        ImmutableSet.of(
            TARGET,
            config.get("cpp_library"),
            config.get("cpp_reflection_library"),
            config.get("cpp2_library")));
  }

  @Test
  public void getGeneratedThriftSources() {

    // Test with no options.
    assertEquals(
        ImmutableList.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_reflection.h",
            "test_reflection.cpp",
            "Test.h",
            "Test.cpp"),
        ENHANCER_CPP.getGeneratedThriftSources(
            ImmutableSet.<String>of(),
            "test.thrift",
            ImmutableList.of("Test")));
    assertEquals(
        ImmutableList.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_types.tcc",
            "Test.h",
            "Test.cpp",
            "Test.tcc"),
        ENHANCER_CPP2.getGeneratedThriftSources(
            ImmutableSet.<String>of(),
            "test.thrift",
            ImmutableList.of("Test")));

    // Test with "frozen" option.
    assertEquals(
        ImmutableList.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_layouts.h",
            "test_layouts.cpp",
            "test_reflection.h",
            "test_reflection.cpp",
            "Test.h",
            "Test.cpp"),
        ENHANCER_CPP.getGeneratedThriftSources(
            ImmutableSet.of("frozen"),
            "test.thrift",
            ImmutableList.of("Test")));
    assertEquals(
        ImmutableList.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_types.tcc",
            "test_layouts.h",
            "test_layouts.cpp",
            "Test.h",
            "Test.cpp",
            "Test.tcc"),
        ENHANCER_CPP2.getGeneratedThriftSources(
            ImmutableSet.of("frozen"),
            "test.thrift",
            ImmutableList.of("Test")));

    // Test with "bootstrap" option.
    assertEquals(
        ImmutableList.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "Test.h",
            "Test.cpp"),
        ENHANCER_CPP.getGeneratedThriftSources(
            ImmutableSet.of("bootstrap"),
            "test.thrift",
            ImmutableList.of("Test")));
    assertEquals(
        ImmutableList.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_types.tcc",
            "Test.h",
            "Test.cpp",
            "Test.tcc"),
        ENHANCER_CPP2.getGeneratedThriftSources(
            ImmutableSet.of("bootstrap"),
            "test.thrift",
            ImmutableList.of("Test")));

    // Test with "templates" option.
    assertEquals(
        ImmutableList.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_types.tcc",
            "test_reflection.h",
            "test_reflection.cpp",
            "Test.h",
            "Test.cpp",
            "Test.tcc"),
        ENHANCER_CPP.getGeneratedThriftSources(
            ImmutableSet.of("templates"),
            "test.thrift",
            ImmutableList.of("Test")));
    assertEquals(
        ImmutableList.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_types.tcc",
            "Test.h",
            "Test.cpp",
            "Test.tcc"),
        ENHANCER_CPP2.getGeneratedThriftSources(
            ImmutableSet.of("templates"),
            "test.thrift",
            ImmutableList.of("Test")));

    // Test with "perfhash" option.
    assertEquals(
        ImmutableList.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_reflection.h",
            "test_reflection.cpp",
            "Test.h",
            "Test.cpp",
            "Test_gperf.tcc"),
        ENHANCER_CPP.getGeneratedThriftSources(
            ImmutableSet.of("perfhash"),
            "test.thrift",
            ImmutableList.of("Test")));
    assertEquals(
        ImmutableList.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_types.tcc",
            "Test.h",
            "Test.cpp",
            "Test.tcc"),
        ENHANCER_CPP2.getGeneratedThriftSources(
            ImmutableSet.of("perfhash"),
            "test.thrift",
            ImmutableList.of("Test")));
  }

  @Test
  public void createBuildRule() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRuleParams flavoredParams =
        BuildRuleParamsFactory.createTrivialBuildRuleParams(TARGET);

    // Add a dummy dependency to the constructor arg to make sure it gets through.
    BuildRule argDep = createFakeBuildRule("//:arg_dep", pathResolver);
    resolver.addToIndex(argDep);
    ThriftConstructorArg arg = new ThriftConstructorArg();
    arg.cpp2Options = Optional.absent();
    arg.cpp2Deps = Optional.of(
        ImmutableSortedSet.of(argDep.getBuildTarget()));

    ThriftCompiler thrift1 = createFakeThriftCompiler("//:thrift_source1", pathResolver);
    resolver.addToIndex(thrift1);
    ThriftCompiler thrift2 = createFakeThriftCompiler("//:thrift_source2", pathResolver);
    resolver.addToIndex(thrift2);

    // Setup up some thrift inputs to pass to the createBuildRule method.
    ImmutableMap<String, ThriftSource> sources = ImmutableMap.of(
        "test1.thrift", new ThriftSource(
            thrift1,
            ImmutableList.<String>of(),
            Paths.get("output1")),
        "test2.thrift", new ThriftSource(
            thrift2,
            ImmutableList.<String>of(),
            Paths.get("output2")));

    // Create a dummy implicit dep to pass in.
    BuildRule dep = createFakeBuildRule("//:dep", pathResolver);
    resolver.addToIndex(dep);
    ImmutableSortedSet<BuildRule> deps = ImmutableSortedSet.of(dep);

    // Run the enhancer to create the language specific build rule.
    ENHANCER_CPP2.createBuildRule(
        flavoredParams,
        resolver,
        arg,
        sources,
        deps);
  }

}
