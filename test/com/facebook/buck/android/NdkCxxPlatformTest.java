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
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessMode;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestSourcePath;
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

import java.io.IOException;
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
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    String source = "source.cpp";
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("source.cpp", Strings.repeat("a", 40))
                    .build()),
            pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    ImmutableMap.Builder<NdkCxxPlatforms.TargetCpuType, RuleKey> ruleKeys =
        ImmutableMap.builder();
    for (Map.Entry<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> entry : cxxPlatforms.entrySet()) {
      CxxSourceRuleFactory cxxSourceRuleFactory =
          new CxxSourceRuleFactory(
              new FakeBuildRuleParamsBuilder(target).build(),
              resolver,
              pathResolver,
              entry.getValue().getCxxPlatform(),
              ImmutableList.<CxxPreprocessorInput>of(),
              ImmutableList.<String>of(),
              Optional.<SourcePath>absent());
      CxxPreprocessAndCompile rule;
      switch (operation) {
        case PREPROCESS_AND_COMPILE:
          rule =
              cxxSourceRuleFactory.createPreprocessAndCompileBuildRule(
                  resolver,
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX,
                      new TestSourcePath(source),
                      ImmutableList.<String>of()),
                  CxxSourceRuleFactory.PicType.PIC,
                  CxxPreprocessMode.COMBINED);
          break;
        case PREPROCESS:
          rule =
              cxxSourceRuleFactory.createPreprocessBuildRule(
                  resolver,
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX,
                      new TestSourcePath(source),
                      ImmutableList.<String>of()),
                  CxxSourceRuleFactory.PicType.PIC);
          break;
        case COMPILE:
          rule =
              cxxSourceRuleFactory.createCompileBuildRule(
                  resolver,
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX_CPP_OUTPUT,
                      new TestSourcePath(source),
                      ImmutableList.<String>of()),
                  CxxSourceRuleFactory.PicType.PIC);
          break;
        default:
          throw new IllegalStateException();
      }
      RuleKeyBuilder builder = ruleKeyBuilderFactory.newInstance(rule);
      ruleKeys.put(entry.getKey(), builder.build());
    }
    return ruleKeys.build();
  }

  // Create and return some rule keys from a dummy source for the given platforms.
  private ImmutableMap<NdkCxxPlatforms.TargetCpuType, RuleKey> constructLinkRuleKeys(
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> cxxPlatforms) {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
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
          TargetGraph.EMPTY,
          entry.getValue().getCxxPlatform(),
          new FakeBuildRuleParamsBuilder(target).build(),
          pathResolver,
          ImmutableList.<String>of(),
          target,
          Linker.LinkType.EXECUTABLE,
          Optional.<String>absent(),
          Paths.get("output"),
          ImmutableList.<SourcePath>of(new TestSourcePath("input.o")),
          /* extraInputs */ ImmutableList.<SourcePath>of(),
          Linker.LinkableDepType.SHARED,
          ImmutableList.<BuildRule>of(),
          Optional.<Linker.CxxRuntimeType>absent(),
          Optional.<SourcePath>absent(),
          ImmutableSet.<BuildRule>of());
      RuleKeyBuilder builder = ruleKeyBuilderFactory.newInstance(rule);
      ruleKeys.put(entry.getKey(), builder.build());
    }
    return ruleKeys.build();
  }

  // The important aspects we check for in rule keys is that the host platform and the path
  // to the NDK don't cause changes.
  @Test
  public void checkRootAndPlatformDoNotAffectRuleKeys() throws IOException {

    // Test all major compiler and runtime combinations.
    ImmutableList<Pair<NdkCxxPlatforms.Compiler.Type, NdkCxxPlatforms.CxxRuntime>> configs =
        ImmutableList.of(
            new Pair<>(NdkCxxPlatforms.Compiler.Type.GCC, NdkCxxPlatforms.CxxRuntime.GNUSTL),
            new Pair<>(NdkCxxPlatforms.Compiler.Type.CLANG, NdkCxxPlatforms.CxxRuntime.GNUSTL),
            new Pair<>(NdkCxxPlatforms.Compiler.Type.CLANG, NdkCxxPlatforms.CxxRuntime.LIBCXX));
    for (Pair<NdkCxxPlatforms.Compiler.Type, NdkCxxPlatforms.CxxRuntime> config : configs) {
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
      for (String dir : ImmutableList.of("something", "something else")) {
        for (Platform platform :
            ImmutableList.of(Platform.LINUX, Platform.MACOS, Platform.WINDOWS)) {
          tmp.create();
          Path root = tmp.newFolder(dir).toPath();
          FakeProjectFilesystem filesystem = new FakeProjectFilesystem(root.toFile());
          filesystem.writeContentsToPath("something", Paths.get("RELEASE.TXT"));
          ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> platforms =
              NdkCxxPlatforms.getPlatforms(
                  filesystem,
                  ImmutableNdkCxxPlatforms.Compiler.builder()
                      .setType(config.getFirst())
                      .setVersion("gcc-version")
                      .setGccVersion("clang-version")
                      .build(),
                  NdkCxxPlatforms.CxxRuntime.GNUSTL,
                  "target-app-platform",
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
