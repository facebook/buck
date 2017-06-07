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

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.DebugPathSanitizer;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class NdkCxxPlatformTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  enum Operation {
    COMPILE,
    PREPROCESS_AND_COMPILE,
  }

  // Create and return some rule keys from a dummy source for the given platforms.
  private ImmutableMap<NdkCxxPlatforms.TargetCpuType, RuleKey> constructCompileRuleKeys(
      Operation operation,
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> cxxPlatforms) {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    String source = "source.cpp";
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(
            0,
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("source.cpp", Strings.repeat("a", 40))
                    .build()),
            pathResolver,
            ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    ImmutableMap.Builder<NdkCxxPlatforms.TargetCpuType, RuleKey> ruleKeys = ImmutableMap.builder();
    for (Map.Entry<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> entry : cxxPlatforms.entrySet()) {
      CxxSourceRuleFactory cxxSourceRuleFactory =
          CxxSourceRuleFactory.builder()
              .setParams(new FakeBuildRuleParamsBuilder(target).build())
              .setResolver(resolver)
              .setPathResolver(pathResolver)
              .setRuleFinder(ruleFinder)
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
                  CxxSource.of(CxxSource.Type.CXX, new FakeSourcePath(source), ImmutableList.of()));
          break;
        case COMPILE:
          rule =
              cxxSourceRuleFactory.createCompileBuildRule(
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX_CPP_OUTPUT,
                      new FakeSourcePath(source),
                      ImmutableList.of()));
          break;
        default:
          throw new IllegalStateException();
      }
      ruleKeys.put(entry.getKey(), ruleKeyFactory.build(rule));
    }
    return ruleKeys.build();
  }

  // Create and return some rule keys from a dummy source for the given platforms.
  private ImmutableMap<NdkCxxPlatforms.TargetCpuType, RuleKey> constructLinkRuleKeys(
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> cxxPlatforms)
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(
            0,
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("input.o", Strings.repeat("a", 40))
                    .build()),
            pathResolver,
            ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    ImmutableMap.Builder<NdkCxxPlatforms.TargetCpuType, RuleKey> ruleKeys = ImmutableMap.builder();
    for (Map.Entry<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> entry : cxxPlatforms.entrySet()) {
      BuildRule rule =
          CxxLinkableEnhancer.createCxxLinkableBuildRule(
              CxxPlatformUtils.DEFAULT_CONFIG,
              entry.getValue().getCxxPlatform(),
              new FakeBuildRuleParamsBuilder(target).build(),
              resolver,
              pathResolver,
              ruleFinder,
              target,
              Linker.LinkType.EXECUTABLE,
              Optional.empty(),
              Paths.get("output"),
              Linker.LinkableDepType.SHARED,
              /* thinLto */ false,
              ImmutableList.of(),
              Optional.empty(),
              Optional.empty(),
              ImmutableSet.of(),
              NativeLinkableInput.builder()
                  .setArgs(SourcePathArg.from(new FakeSourcePath("input.o")))
                  .build(),
              Optional.empty());
      ruleKeys.put(entry.getKey(), ruleKeyFactory.build(rule));
    }
    return ruleKeys.build();
  }

  @Test
  public void testNdkMajorVersion() {
    assertEquals(9, NdkCxxPlatforms.getNdkMajorVersion("r9"));
    assertEquals(9, NdkCxxPlatforms.getNdkMajorVersion("r9b"));
    assertEquals(10, NdkCxxPlatforms.getNdkMajorVersion("r10c"));
    assertEquals(10, NdkCxxPlatforms.getNdkMajorVersion("r10e"));
    assertEquals(11, NdkCxxPlatforms.getNdkMajorVersion("11.0.1234"));
    assertEquals(11, NdkCxxPlatforms.getNdkMajorVersion("11.2.2725575"));
    assertEquals(12, NdkCxxPlatforms.getNdkMajorVersion("12.0.1234"));
    assertEquals(12, NdkCxxPlatforms.getNdkMajorVersion("12.1.2977051"));
  }

  @Test
  public void testNdkFlags() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    Path ndkRoot = tmp.newFolder("android-ndk-r10b");
    NdkCxxPlatformTargetConfiguration targetConfiguration =
        NdkCxxPlatforms.getTargetConfiguration(
            NdkCxxPlatforms.TargetCpuType.X86,
            NdkCxxPlatformCompiler.builder()
                .setType(NdkCxxPlatformCompiler.Type.GCC)
                .setVersion("gcc-version")
                .setGccVersion("clang-version")
                .build(),
            "target-app-platform");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9c"), ndkRoot.resolve("RELEASE.TXT"));
    NdkCxxPlatform platform =
        NdkCxxPlatforms.build(
            CxxPlatformUtils.DEFAULT_CONFIG,
            new AndroidBuckConfig(FakeBuckConfig.builder().build(), Platform.detect()),
            filesystem,
            InternalFlavor.of("android-x86"),
            Platform.detect(),
            ndkRoot,
            targetConfiguration,
            NdkCxxRuntime.GNUSTL,
            new AlwaysFoundExecutableFinder(),
            false /* strictToolchainPaths */);
    assertThat(platform.getCxxPlatform().getCflags(), hasItems("-std=gnu11", "-O2"));
    assertThat(
        platform.getCxxPlatform().getCxxflags(),
        hasItems("-std=gnu++11", "-O2", "-fno-exceptions", "-fno-rtti"));
    assertThat(platform.getCxxPlatform().getCppflags(), hasItems("-std=gnu11", "-O2"));
  }

  @Test
  public void testExtraNdkFlags() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    Path ndkRoot = tmp.newFolder("android-ndk-r10b");
    NdkCxxPlatformTargetConfiguration targetConfiguration =
        NdkCxxPlatforms.getTargetConfiguration(
            NdkCxxPlatforms.TargetCpuType.X86,
            NdkCxxPlatformCompiler.builder()
                .setType(NdkCxxPlatformCompiler.Type.GCC)
                .setVersion("gcc-version")
                .setGccVersion("clang-version")
                .build(),
            "target-app-platform");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9c"), ndkRoot.resolve("RELEASE.TXT"));
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "ndk",
                        ImmutableMap.of(
                            "extra_cflags", "-DSOME_CFLAG -DBUCK -std=buck -Og -Wno-buck",
                            "extra_cxxflags",
                                "-DSOME_CXXFLAG -DBUCK -std=buck++ -Og -Wno-buck -frtti -fexceptions"),
                    "cxx",
                        ImmutableMap.of(
                            "cxxflags", "-Wignored-cxx-flag",
                            "cflags", "-Wignored-c-flag",
                            "cppflags", "-Wignored-cpp-flag")))
            .build();
    NdkCxxPlatform platform =
        NdkCxxPlatforms.build(
            new CxxBuckConfig(buckConfig),
            new AndroidBuckConfig(buckConfig, Platform.detect()),
            filesystem,
            InternalFlavor.of("android-x86"),
            Platform.detect(),
            ndkRoot,
            targetConfiguration,
            NdkCxxRuntime.GNUSTL,
            new AlwaysFoundExecutableFinder(),
            false /* strictToolchainPaths */);

    // Check that we can add new flags and that we can actually override things like
    // warning/optimazation/etc.
    ImmutableList<String> cppflags = platform.getCxxPlatform().getCppflags();
    assertThat(
        cppflags, hasItems("-std=buck", "-O2", "-Og", "-DSOME_CFLAG", "-DBUCK", "-Wno-buck"));
    assertThat(cppflags, not(hasItems("-DSOME_CXXFLAG")));
    assertLastMatchingFlagIs(cppflags, f -> f.startsWith("-O"), "-Og");
    assertLastMatchingFlagIs(cppflags, f -> f.startsWith("-std="), "-std=buck");

    ImmutableList<String> cflags = platform.getCxxPlatform().getCflags();
    assertThat(cflags, hasItems("-Og", "-O2", "-std=buck", "-DSOME_CFLAG", "-DBUCK", "-Wno-buck"));
    assertThat(cflags, not(hasItem("-DSOME_CXXFLAG")));

    assertLastMatchingFlagIs(cflags, f -> f.startsWith("-O"), "-Og");
    assertLastMatchingFlagIs(cflags, f -> f.startsWith("-W"), "-Wno-buck");
    assertLastMatchingFlagIs(cflags, f -> f.startsWith("-std="), "-std=buck");

    ImmutableList<String> cxxppflags = platform.getCxxPlatform().getCxxppflags();
    assertThat(cxxppflags, hasItems("-O2", "-DSOME_CXXFLAG", "-DBUCK", "-Og", "-Wno-buck"));
    assertThat(cxxppflags, not(hasItem("-DSOME_CFLAG")));
    assertLastMatchingFlagIs(cxxppflags, f -> f.startsWith("-O"), "-Og");
    assertLastMatchingFlagIs(cxxppflags, f -> f.startsWith("-std="), "-std=buck++");

    ImmutableList<String> cxxflags = platform.getCxxPlatform().getCxxflags();
    assertThat(
        cxxflags,
        hasItems(
            "-std=gnu++11",
            "-O2",
            "-DSOME_CXXFLAG",
            "-DBUCK",
            "-Og",
            "-fno-exceptions",
            "-fno-rtti",
            "-Wno-buck"));
    assertThat(cxxflags, not(hasItem("-DSOME_CFLAG")));
    assertLastMatchingFlagIs(cxxflags, f -> f.startsWith("-O"), "-Og");
    assertLastMatchingFlagIs(cxxflags, f -> f.startsWith("-std="), "-std=buck++");
    assertLastMatchingFlagIs(cxxflags, f -> f.equals("-frtti") || f.equals("-fno-rtti"), "-frtti");
    assertLastMatchingFlagIs(
        cxxflags, f -> f.equals("-fexceptions") || f.equals("-fno-exceptions"), "-fexceptions");
  }

  private void assertLastMatchingFlagIs(
      ImmutableList<String> flags, Function<String, Boolean> filter, String expected) {
    assertThat(flags, hasItems(expected));
    for (String flag : flags.reverse()) {
      if (filter.apply(flag)) {
        assertThat(flag, is(expected));
        return;
      }
    }
  }

  // This test is mostly just so that changes are forced to update this string if they change the
  // ndk platform flags so that such changes can actually be reviewed.
  @Test
  public void testExtraNdkFlagsLiterally() throws IOException, InterruptedException {
    Assume.assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    Path ndkRoot = tmp.newFolder("android-ndk-r10b");
    NdkCxxPlatformTargetConfiguration targetConfiguration =
        NdkCxxPlatforms.getTargetConfiguration(
            NdkCxxPlatforms.TargetCpuType.X86,
            NdkCxxPlatformCompiler.builder()
                .setType(NdkCxxPlatformCompiler.Type.GCC)
                .setVersion("gcc-version")
                .setGccVersion("clang-version")
                .build(),
            "target-app-platform");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9c"), ndkRoot.resolve("RELEASE.TXT"));
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "ndk",
                    ImmutableMap.of(
                        "extra_cflags",
                        "--start-extra-cflags-- -DSOME_CFLAG --end-extra-cflags--",
                        "extra_cxxflags",
                        "--start-extra-cxxflags-- -DSOME_CXXFLAG --end-extra-cxxflags--"),
                    "cxx",
                    ImmutableMap.of(
                        "cxxflags", "-Wignored-cxx-flag",
                        "cflags", "-Wignored-c-flag",
                        "cppflags", "-Wignored-cpp-flag")))
            .build();
    NdkCxxPlatform platform =
        NdkCxxPlatforms.build(
            new CxxBuckConfig(buckConfig),
            new AndroidBuckConfig(buckConfig, Platform.detect()),
            filesystem,
            InternalFlavor.of("android-x86"),
            Platform.detect(),
            ndkRoot,
            targetConfiguration,
            NdkCxxRuntime.GNUSTL,
            new AlwaysFoundExecutableFinder(),
            false /* strictToolchainPaths */);

    Joiner joiner = Joiner.on("\n");
    CxxPlatform cxxPlatform = platform.getCxxPlatform();
    DebugPathSanitizer sanitizer = cxxPlatform.getCompilerDebugPathSanitizer();
    Path expectedFlags = TestDataHelper.getTestDataDirectory(this).resolve("ndkcxxplatforms.flags");
    String expected = Files.toString(expectedFlags.toFile(), Charsets.UTF_8);

    // This string is constructed to try to get intellij/phabricator to not detect moves across the
    // boundaries of different flag types. We could split this into multiple files, but the tradeoff
    // there is then that changes need to update all those files and reviewing the changes is across
    // multiple files.
    String actual =
        String.format(
            "---BEGIN CFLAGS-------\n"
                + "%s\n"
                + "---END CFLAGS---------\n"
                + "----------------------\n"
                + "======================\n"
                + "----------------------\n"
                + "---BEGIN CPPFLAGS-----\n"
                + "%s\n"
                + "---END CPPFLAGS-------\n"
                + "----------------------\n"
                + "======================\n"
                + "----------------------\n"
                + "---BEGIN CXXFLAGS-----\n"
                + "%s\n"
                + "---END CXXFLAGS-------\n"
                + "----------------------\n"
                + "======================\n"
                + "----------------------\n"
                + "---BEGIN CXXPPFLAGS---\n"
                + "%s\n"
                + "---END CXXPPFLAGS-----\n",
            joiner.join(sanitizer.sanitizeFlags(cxxPlatform.getCflags())),
            joiner.join(sanitizer.sanitizeFlags(cxxPlatform.getCppflags())),
            joiner.join(sanitizer.sanitizeFlags(cxxPlatform.getCxxflags())),
            joiner.join(sanitizer.sanitizeFlags(cxxPlatform.getCxxppflags())));
    // Use assertEquals instead of assertThat because Intellij's handling of failures of
    // assertEquals is more user-friendly than for assertThat.
    assertEquals(expected, actual);
  }

  // The important aspects we check for in rule keys is that the host platform and the path
  // to the NDK don't cause changes.
  @Test
  public void checkRootAndPlatformDoNotAffectRuleKeys() throws Exception {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());

    // Test all major compiler and runtime combinations.
    ImmutableList<Pair<NdkCxxPlatformCompiler.Type, NdkCxxRuntime>> configs =
        ImmutableList.of(
            new Pair<>(NdkCxxPlatformCompiler.Type.GCC, NdkCxxRuntime.GNUSTL),
            new Pair<>(NdkCxxPlatformCompiler.Type.CLANG, NdkCxxRuntime.GNUSTL),
            new Pair<>(NdkCxxPlatformCompiler.Type.CLANG, NdkCxxRuntime.LIBCXX));
    for (Pair<NdkCxxPlatformCompiler.Type, NdkCxxRuntime> config : configs) {
      Map<String, ImmutableMap<NdkCxxPlatforms.TargetCpuType, RuleKey>>
          preprocessAndCompileRukeKeys = new HashMap<>();
      Map<String, ImmutableMap<NdkCxxPlatforms.TargetCpuType, RuleKey>> compileRukeKeys =
          new HashMap<>();
      Map<String, ImmutableMap<NdkCxxPlatforms.TargetCpuType, RuleKey>> linkRukeKeys =
          new HashMap<>();

      // Iterate building up rule keys for combinations of different platforms and NDK root
      // directories.
      for (String dir : ImmutableList.of("android-ndk-r9c", "android-ndk-r10b")) {
        for (Platform platform :
            ImmutableList.of(Platform.LINUX, Platform.MACOS, Platform.WINDOWS)) {
          Path root = tmp.newFolder(dir);
          MoreFiles.writeLinesToFile(ImmutableList.of("r9c"), root.resolve("RELEASE.TXT"));
          ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> platforms =
              NdkCxxPlatforms.getPlatforms(
                  CxxPlatformUtils.DEFAULT_CONFIG,
                  new AndroidBuckConfig(FakeBuckConfig.builder().build(), platform),
                  filesystem,
                  root,
                  NdkCxxPlatformCompiler.builder()
                      .setType(config.getFirst())
                      .setVersion("gcc-version")
                      .setGccVersion("clang-version")
                      .build(),
                  NdkCxxRuntime.GNUSTL,
                  "target-app-platform",
                  ImmutableSet.of("x86"),
                  platform,
                  new AlwaysFoundExecutableFinder(),
                  /* strictToolchainPaths */ false);
          preprocessAndCompileRukeKeys.put(
              String.format("NdkCxxPlatform(%s, %s)", dir, platform),
              constructCompileRuleKeys(Operation.PREPROCESS_AND_COMPILE, platforms));
          compileRukeKeys.put(
              String.format("NdkCxxPlatform(%s, %s)", dir, platform),
              constructCompileRuleKeys(Operation.COMPILE, platforms));
          linkRukeKeys.put(
              String.format("NdkCxxPlatform(%s, %s)", dir, platform),
              constructLinkRuleKeys(platforms));
          MoreFiles.deleteRecursively(root);
        }
      }

      // If everything worked, we should be able to collapse all the generated rule keys down
      // to a singleton set.
      assertThat(
          Arrays.toString(preprocessAndCompileRukeKeys.entrySet().toArray()),
          Sets.newHashSet(preprocessAndCompileRukeKeys.values()),
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

  @Test
  public void headerVerificationWhitelistsNdkRoot() throws InterruptedException, IOException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    String dir = "android-ndk-r9c";
    Path root = tmp.newFolder(dir);
    MoreFiles.writeLinesToFile(ImmutableList.of("r9c"), root.resolve("RELEASE.TXT"));
    ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> platforms =
        NdkCxxPlatforms.getPlatforms(
            CxxPlatformUtils.DEFAULT_CONFIG,
            new AndroidBuckConfig(FakeBuckConfig.builder().build(), Platform.detect()),
            filesystem,
            root,
            NdkCxxPlatformCompiler.builder()
                .setType(NdkCxxPlatformCompiler.Type.GCC)
                .setVersion("gcc-version")
                .setGccVersion("clang-version")
                .build(),
            NdkCxxRuntime.GNUSTL,
            "target-app-platform",
            ImmutableSet.of("x86"),
            Platform.LINUX,
            new AlwaysFoundExecutableFinder(),
            /* strictToolchainPaths */ false);
    for (NdkCxxPlatform ndkCxxPlatform : platforms.values()) {
      assertTrue(
          ndkCxxPlatform
              .getCxxPlatform()
              .getHeaderVerification()
              .isWhitelisted(root.resolve("test.h").toString()));
    }
  }
}
