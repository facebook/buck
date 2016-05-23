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

package com.facebook.buck.android;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessMode;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;

public class NdkCxxPlatformTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  enum Operation {
    PREPROCESS,
    COMPILE,
    PREPROCESS_AND_COMPILE,
  }

  // Create and return some rule keys from a dummy source for the given platforms.
  private ImmutableMap<NdkCxxPlatforms.TargetCpuType, RuleKey> constructCompileRuleKeys(
      Operation operation,
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> cxxPlatforms) {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    String source = "source.cpp";
    DefaultRuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            0,
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("source.cpp", Strings.repeat("a", 40))
                    .build()),
            pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    ImmutableMap.Builder<NdkCxxPlatforms.TargetCpuType, RuleKey> ruleKeys =
        ImmutableMap.builder();
    for (Map.Entry<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> entry : cxxPlatforms.entrySet()) {
      CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactory.builder()
          .setParams(new FakeBuildRuleParamsBuilder(target).build())
          .setResolver(resolver)
          .setPathResolver(pathResolver)
          .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
          .setCxxPlatform(entry.getValue().getCxxPlatform())
          .setPicType(CxxSourceRuleFactory.PicType.PIC)
          .build();
      CxxPreprocessAndCompile rule;
      switch (operation) {
        case PREPROCESS_AND_COMPILE:
          rule =
              cxxSourceRuleFactory.createPreprocessAndCompileBuildRule(
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX,
                      new FakeSourcePath(source),
                      ImmutableList.<String>of()),
                  CxxPreprocessMode.COMBINED);
          break;
        case PREPROCESS:
          rule =
              cxxSourceRuleFactory.createPreprocessBuildRule(
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX,
                      new FakeSourcePath(source),
                      ImmutableList.<String>of()));
          break;
        case COMPILE:
          rule =
              cxxSourceRuleFactory.createCompileBuildRule(
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX_CPP_OUTPUT,
                      new FakeSourcePath(source),
                      ImmutableList.<String>of()));
          break;
        default:
          throw new IllegalStateException();
      }
      ruleKeys.put(entry.getKey(), ruleKeyBuilderFactory.build(rule));
    }
    return ruleKeys.build();
  }

  // Create and return some rule keys from a dummy source for the given platforms.
  private ImmutableMap<NdkCxxPlatforms.TargetCpuType, RuleKey> constructLinkRuleKeys(
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> cxxPlatforms)
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    DefaultRuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            0,
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("input.o", Strings.repeat("a", 40))
                    .build()),
            pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    ImmutableMap.Builder<NdkCxxPlatforms.TargetCpuType, RuleKey> ruleKeys =
        ImmutableMap.builder();
    for (Map.Entry<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> entry : cxxPlatforms.entrySet()) {
      BuildRule rule = CxxLinkableEnhancer.createCxxLinkableBuildRule(
          CxxPlatformUtils.DEFAULT_CONFIG,
          entry.getValue().getCxxPlatform(),
          new FakeBuildRuleParamsBuilder(target).build(),
          resolver,
          pathResolver,
          target,
          Linker.LinkType.EXECUTABLE,
          Optional.<String>absent(),
          Paths.get("output"),
          Linker.LinkableDepType.SHARED,
          ImmutableList.<NativeLinkable>of(),
          Optional.<Linker.CxxRuntimeType>absent(),
          Optional.<SourcePath>absent(),
          ImmutableSet.<BuildTarget>of(),
          NativeLinkableInput.builder()
              .setArgs(SourcePathArg.from(pathResolver, new FakeSourcePath("input.o")))
              .build());
      ruleKeys.put(entry.getKey(), ruleKeyBuilderFactory.build(rule));
    }
    return ruleKeys.build();
  }

  // The important aspects we check for in rule keys is that the host platform and the path
  // to the NDK don't cause changes.
  @Test
  public void checkRootAndPlatformDoNotAffectRuleKeys() throws Exception {

    // Test all major compiler and runtime combinations.
    ImmutableList<Pair<NdkCxxPlatformCompiler.Type, NdkCxxPlatforms.CxxRuntime>> configs =
        ImmutableList.of(
            new Pair<>(NdkCxxPlatformCompiler.Type.GCC, NdkCxxPlatforms.CxxRuntime.GNUSTL),
            new Pair<>(NdkCxxPlatformCompiler.Type.CLANG, NdkCxxPlatforms.CxxRuntime.GNUSTL),
            new Pair<>(NdkCxxPlatformCompiler.Type.CLANG, NdkCxxPlatforms.CxxRuntime.LIBCXX));
    for (Pair<NdkCxxPlatformCompiler.Type, NdkCxxPlatforms.CxxRuntime> config : configs) {
      Map<String, ImmutableMap<NdkCxxPlatforms.TargetCpuType, RuleKey>>
          preprocessAndCompileRukeKeys = Maps.newHashMap();
      Map<String, ImmutableMap<NdkCxxPlatforms.TargetCpuType, RuleKey>>
          preprocessRukeKeys = Maps.newHashMap();
      Map<String, ImmutableMap<NdkCxxPlatforms.TargetCpuType, RuleKey>>
          compileRukeKeys = Maps.newHashMap();
      Map<String, ImmutableMap<NdkCxxPlatforms.TargetCpuType, RuleKey>>
          linkRukeKeys = Maps.newHashMap();

      // Iterate building up rule keys for combinations of different platforms and NDK root
      // directories.
      for (String dir : ImmutableList.of("android-ndk-r9c", "android-ndk-r10b")) {
        for (Platform platform :
            ImmutableList.of(Platform.LINUX, Platform.MACOS, Platform.WINDOWS)) {
          tmp.create();
          Path root = tmp.newFolder(dir).toPath();
          FakeProjectFilesystem filesystem = new FakeProjectFilesystem(root.toFile());
          MoreFiles.writeLinesToFile(ImmutableList.of("r9c"), root.resolve("RELEASE.TXT"));
          ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> platforms =
              NdkCxxPlatforms.getPlatforms(
                  filesystem,
                  NdkCxxPlatformCompiler.builder()
                      .setType(config.getFirst())
                      .setVersion("gcc-version")
                      .setGccVersion("clang-version")
                      .build(),
                  NdkCxxPlatforms.CxxRuntime.GNUSTL,
                  "target-app-platform",
                  ImmutableSet.of("x86"),
                  platform,
                  new AlwaysFoundExecutableFinder());
          preprocessAndCompileRukeKeys.put(
              String.format("NdkCxxPlatform(%s, %s)", dir, platform),
              constructCompileRuleKeys(Operation.PREPROCESS_AND_COMPILE, platforms));
          preprocessRukeKeys.put(
              String.format("NdkCxxPlatform(%s, %s)", dir, platform),
              constructCompileRuleKeys(Operation.PREPROCESS, platforms));
          compileRukeKeys.put(
              String.format("NdkCxxPlatform(%s, %s)", dir, platform),
              constructCompileRuleKeys(Operation.COMPILE, platforms));
          linkRukeKeys.put(
              String.format("NdkCxxPlatform(%s, %s)", dir, platform),
              constructLinkRuleKeys(platforms));
          tmp.delete();
        }
      }

      // If everything worked, we should be able to collapse all the generated rule keys down
      // to a singleton set.
      assertThat(
          Arrays.toString(preprocessAndCompileRukeKeys.entrySet().toArray()),
          Sets.newHashSet(preprocessAndCompileRukeKeys.values()),
          Matchers.hasSize(1));
      assertThat(
          Arrays.toString(preprocessRukeKeys.entrySet().toArray()),
          Sets.newHashSet(preprocessRukeKeys.values()),
          Matchers.hasSize(1));
      assertThat(
          Arrays.toString(compileRukeKeys.entrySet().toArray()),
          Sets.newHashSet(compileRukeKeys.values()),
          Matchers.hasSize(1));
      assertThat(
          Arrays.toString(linkRukeKeys.entrySet().toArray()),
          Sets.newHashSet(linkRukeKeys.values()),
          Matchers.hasSize(1));
    }

  }

}
