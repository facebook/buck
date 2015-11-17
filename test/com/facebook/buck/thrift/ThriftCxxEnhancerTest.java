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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessMode;
import com.facebook.buck.cxx.DefaultCxxPlatforms;
import com.facebook.buck.cxx.InferBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.rules.coercer.SourceWithFlagsList;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ThriftCxxEnhancerTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:test#cpp");
  private static final BuckConfig BUCK_CONFIG = FakeBuckConfig.builder().build();
  private static final ThriftBuckConfig THRIFT_BUCK_CONFIG = new ThriftBuckConfig(BUCK_CONFIG);
  private static final CxxBuckConfig CXX_BUCK_CONFIG = new CxxBuckConfig(BUCK_CONFIG);
  private static final CxxPlatform CXX_PLATFORM = DefaultCxxPlatforms.build(
      new CxxBuckConfig(BUCK_CONFIG));
  private static final FlavorDomain<CxxPlatform> CXX_PLATFORMS =
      new FlavorDomain<>(
          "C/C++ Platform",
          ImmutableMap.of(CXX_PLATFORM.getFlavor(), CXX_PLATFORM));
  private static final CxxLibraryDescription CXX_LIBRARY_DESCRIPTION =
      new CxxLibraryDescription(
          CXX_BUCK_CONFIG,
          new InferBuckConfig(BUCK_CONFIG),
          CXX_PLATFORMS,
          CxxPreprocessMode.SEPARATE);
  private static final ThriftCxxEnhancer ENHANCER_CPP =
      new ThriftCxxEnhancer(
          THRIFT_BUCK_CONFIG,
          CXX_LIBRARY_DESCRIPTION,
          /* cpp2 */ false);
  private static final ThriftCxxEnhancer ENHANCER_CPP2 =
      new ThriftCxxEnhancer(
          THRIFT_BUCK_CONFIG,
          CXX_LIBRARY_DESCRIPTION,
          /* cpp2 */ true);

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

  private static ThriftCompiler createFakeThriftCompiler(
      String target,
      SourcePathResolver resolver) {
    return new ThriftCompiler(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target)).build(),
        resolver,
        new CommandTool.Builder()
            .addArg(new FakeSourcePath("compiler"))
            .build(),
        ImmutableList.<String>of(),
        Paths.get("output"),
        new FakeSourcePath("source"),
        "language",
        ImmutableSet.<String>of(),
        ImmutableList.<Path>of(),
        ImmutableSet.<Path>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSortedSet.<String>of());
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

  @Test
  public void getCompiler() {
    assertEquals(
        ThriftLibraryDescription.CompilerType.THRIFT,
        ENHANCER_CPP.getCompilerType());
    assertEquals(
        ThriftLibraryDescription.CompilerType.THRIFT2,
        ENHANCER_CPP2.getCompilerType());
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
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(
        ImmutableMap.of("thrift", strConfig.build())).build();
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);
    ThriftCxxEnhancer cppEnhancerWithSettings = new ThriftCxxEnhancer(
        thriftBuckConfig,
        CXX_LIBRARY_DESCRIPTION,
        /* cpp2 */ false);
    ThriftCxxEnhancer cpp2EnhancerWithSettings = new ThriftCxxEnhancer(
        thriftBuckConfig,
        CXX_LIBRARY_DESCRIPTION,
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
  public void getGeneratedSources() {

    // Test with no options.
    assertEquals(
        ImmutableSortedSet.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_reflection.h",
            "test_reflection.cpp",
            "Test.h",
            "Test.cpp"),
        ENHANCER_CPP.getGeneratedSources(
            "test.thrift",
            ImmutableList.of("Test"),
            ImmutableSet.<String>of()));
    assertEquals(
        ImmutableSortedSet.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_types.tcc",
            "test_types_custom_protocol.h",
            "Test.h",
            "Test.cpp",
            "Test_client.cpp",
            "Test_custom_protocol.h",
            "Test.tcc"),
        ENHANCER_CPP2.getGeneratedSources(
            "test.thrift",
            ImmutableList.of("Test"),
            ImmutableSet.<String>of()));

    // Test with "frozen" option.
    assertEquals(
        ImmutableSortedSet.of(
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
        ENHANCER_CPP.getGeneratedSources(
            "test.thrift",
            ImmutableList.of("Test"),
            ImmutableSet.of("frozen2")));
    assertEquals(
        ImmutableSortedSet.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_types.tcc",
            "test_layouts.h",
            "test_layouts.cpp",
            "test_types_custom_protocol.h",
            "Test.h",
            "Test.cpp",
            "Test_client.cpp",
            "Test_custom_protocol.h",
            "Test.tcc"),
        ENHANCER_CPP2.getGeneratedSources(
            "test.thrift",
            ImmutableList.of("Test"),
            ImmutableSet.of("frozen2")));

    // Test with "bootstrap" option.
    assertEquals(
        ImmutableSortedSet.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "Test.h",
            "Test.cpp"),
        ENHANCER_CPP.getGeneratedSources(
            "test.thrift",
            ImmutableList.of("Test"),
            ImmutableSet.of("bootstrap")));
    assertEquals(
        ImmutableSortedSet.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_types.tcc",
            "test_types_custom_protocol.h",
            "Test.h",
            "Test.cpp",
            "Test_client.cpp",
            "Test_custom_protocol.h",
            "Test.tcc"),
        ENHANCER_CPP2.getGeneratedSources(
            "test.thrift",
            ImmutableList.of("Test"),
            ImmutableSet.of("bootstrap")));

    // Test with "templates" option.
    assertEquals(
        ImmutableSortedSet.of(
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
        ENHANCER_CPP.getGeneratedSources(
            "test.thrift",
            ImmutableList.of("Test"),
            ImmutableSet.of("templates")));
    assertEquals(
        ImmutableSortedSet.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_types.tcc",
            "test_types_custom_protocol.h",
            "Test.h",
            "Test.cpp",
            "Test_client.cpp",
            "Test_custom_protocol.h",
            "Test.tcc"),
        ENHANCER_CPP2.getGeneratedSources(
            "test.thrift",
            ImmutableList.of("Test"),
            ImmutableSet.of("templates")));

    // Test with "perfhash" option.
    assertEquals(
        ImmutableSortedSet.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_reflection.h",
            "test_reflection.cpp",
            "Test.h",
            "Test.cpp",
            "Test_gperf.tcc"),
        ENHANCER_CPP.getGeneratedSources(
            "test.thrift",
            ImmutableList.of("Test"),
            ImmutableSet.of("perfhash")));
    assertEquals(
        ImmutableSortedSet.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_types.tcc",
            "test_types_custom_protocol.h",
            "Test.h",
            "Test.cpp",
            "Test_client.cpp",
            "Test_custom_protocol.h",
            "Test.tcc"),
        ENHANCER_CPP2.getGeneratedSources(
            "test.thrift",
            ImmutableList.of("Test"),
            ImmutableSet.of("perfhash")));

    // Test with "separate_processmap" option.
    assertEquals(
        ImmutableSortedSet.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_reflection.h",
            "test_reflection.cpp",
            "Test.h",
            "Test.cpp"),
        ENHANCER_CPP.getGeneratedSources(
            "test.thrift",
            ImmutableList.of("Test"),
            ImmutableSet.of("separate_processmap")));
    assertEquals(
        ImmutableSortedSet.of(
            "test_constants.h",
            "test_constants.cpp",
            "test_types.h",
            "test_types.cpp",
            "test_types.tcc",
            "test_types_custom_protocol.h",
            "Test.h",
            "Test.cpp",
            "Test_client.cpp",
            "Test_custom_protocol.h",
            "Test_processmap_binary.cpp",
            "Test_processmap_compact.cpp",
            "Test.tcc"),
        ENHANCER_CPP2.getGeneratedSources(
            "test.thrift",
            ImmutableList.of("Test"),
            ImmutableSet.of("separate_processmap")));
  }

  @Test
  public void createBuildRule() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRuleParams flavoredParams = new FakeBuildRuleParamsBuilder(TARGET).build();

    // Add a dummy dependency to the constructor arg to make sure it gets through.
    BuildRule argDep = createFakeBuildRule("//:arg_dep", pathResolver);
    resolver.addToIndex(argDep);
    ThriftConstructorArg arg = new ThriftConstructorArg();
    arg.cppHeaderNamespace = Optional.absent();
    arg.cppExportedHeaders = Optional.absent();
    arg.cppSrcs = Optional.absent();
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
        TargetGraph.EMPTY,
        flavoredParams,
        resolver,
        arg,
        sources,
        deps);
  }

  @Test
  public void cppSrcsAndHeadersArePropagated() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRuleParams flavoredParams = new FakeBuildRuleParamsBuilder(TARGET).build();

    final String cppHeaderNamespace = "foo";
    final ImmutableSortedMap<String, SourcePath> cppHeaders =
        ImmutableSortedMap.<String, SourcePath>of(
            "header.h", new FakeSourcePath("header.h"));
    final ImmutableSortedMap<String, SourceWithFlags> cppSrcs =
        ImmutableSortedMap.of(
            "source.cpp", SourceWithFlags.of(new FakeSourcePath("source.cpp")));

    ThriftConstructorArg arg = new ThriftConstructorArg();
    arg.cppOptions = Optional.absent();
    arg.cppDeps = Optional.absent();
    arg.cppHeaderNamespace = Optional.of(cppHeaderNamespace);
    arg.cppExportedHeaders = Optional.of(SourceList.ofNamedSources(cppHeaders));
    arg.cppSrcs = Optional.of(SourceWithFlagsList.ofNamedSources(cppSrcs));

    ThriftCompiler thrift = createFakeThriftCompiler("//:thrift_source", pathResolver);
    resolver.addToIndex(thrift);

    // Setup up some thrift inputs to pass to the createBuildRule method.
    ImmutableMap<String, ThriftSource> sources = ImmutableMap.of(
        "test.thrift", new ThriftSource(
            thrift,
            ImmutableList.<String>of(),
            Paths.get("output")));

    // Run the enhancer with a modified C++ description which checks that appropriate args are
    // propagated.
    CxxLibraryDescription cxxLibraryDescription =
        new CxxLibraryDescription(
            CXX_BUCK_CONFIG,
            new InferBuckConfig(BUCK_CONFIG),
            CXX_PLATFORMS,
            CxxPreprocessMode.SEPARATE) {
          @Override
          public <A extends Arg> BuildRule createBuildRule(
              TargetGraph targetGraph,
              BuildRuleParams params,
              BuildRuleResolver resolver,
              A args) {
            assertThat(args.headerNamespace, Matchers.equalTo(Optional.of(cppHeaderNamespace)));
            for (Map.Entry<String, SourcePath> header : cppHeaders.entrySet()) {
              assertThat(
                  args.exportedHeaders.get().getNamedSources().get().get(header.getKey()),
                  Matchers.equalTo(header.getValue()));
            }
            for (Map.Entry<String, SourceWithFlags> source : cppSrcs.entrySet()) {
              assertThat(
                  args.srcs.get(),
                  Matchers.hasItem(source.getValue()));
            }
            return super.createBuildRule(targetGraph, params, resolver, args);
          }
        };
    ThriftCxxEnhancer enhancer =
        new ThriftCxxEnhancer(
            THRIFT_BUCK_CONFIG,
            cxxLibraryDescription,
          /* cpp2 */ false);
    enhancer.createBuildRule(
        TargetGraph.EMPTY,
        flavoredParams,
        resolver,
        arg,
        sources,
        ImmutableSortedSet.<BuildRule>of());
  }

}
