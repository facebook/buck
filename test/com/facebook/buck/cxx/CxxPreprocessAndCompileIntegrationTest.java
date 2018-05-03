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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.Parameterized;

public class CxxPreprocessAndCompileIntegrationTest {

  @Parameterized.Parameters(name = "sandbox_sources={0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(new Object[] {true}, new Object[] {false});
  }

  @Parameterized.Parameter(0)
  public boolean sandboxSource;

  @Rule public TemporaryPaths tmp = new TemporaryPaths("cxx_pp_and_compile");
  @Rule public TemporaryPaths tmp_long_pwd = new TemporaryPaths("cxx_pp_and_compiler_long_pwd");

  private ProjectWorkspace workspace;

  private ProjectWorkspace setupWorkspace(TemporaryPaths tmp) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "step_test", tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[cxx]\n"
            + "  sandbox_sources = "
            + sandboxSource
            + "\n"
            + "  asflags = -g\n"
            + "  cppflags = -g\n"
            + "  cflags = -g\n"
            + "  cxxppflags = -g\n"
            + "  cxxflags = -g\n"
            + "[build]\n"
            + "  depfiles = enabled\n",
        ".buckconfig");
    return workspace;
  }

  @Before
  public void setUp() throws IOException {
    workspace = setupWorkspace(tmp);
  }

  @Test
  public void sanitizeWorkingDirectory() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//:simple#default,static");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    Path lib = workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s/libsimple.a"));
    String contents = Files.asByteSource(lib.toFile()).asCharSource(Charsets.ISO_8859_1).read();
    assertFalse(lib.toString(), contents.contains(tmp.getRoot().toString()));

    // ...
    ProjectWorkspace longPwdWorkspace = setupWorkspace(tmp_long_pwd);
    ProjectFilesystem longPwdFilesystem = new FakeProjectFilesystem();
    longPwdWorkspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    Path longPwdLib =
        longPwdWorkspace.getPath(
            BuildTargets.getGenPath(longPwdFilesystem, target, "%s/libsimple.a"));
    String longPwdContents =
        Files.asByteSource(longPwdLib.toFile()).asCharSource(Charsets.ISO_8859_1).read();
    assertEquals(contents, longPwdContents);
  }

  @Test
  public void sanitizeWorkingDirectoryWhenBuildingAssembly() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//:simple_assembly#default,static");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ProcessResult processResult = workspace.runBuckBuild(target.getFullyQualifiedName());
    processResult.assertSuccess();
    Path lib =
        workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s/libsimple_assembly.a"));
    String contents = Files.asByteSource(lib.toFile()).asCharSource(Charsets.ISO_8859_1).read();
    assertFalse(lib.toString(), contents.contains(tmp.getRoot().toString()));
  }

  @Test
  public void sanitizeSymlinkedWorkingDirectory() throws IOException {
    TemporaryFolder folder = new TemporaryFolder();
    folder.create();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(folder.getRoot().toPath());

    // Setup up a symlink to our working directory.
    Path symlinkedRoot = folder.getRoot().toPath().resolve("symlinked-root");
    java.nio.file.Files.createSymbolicLink(symlinkedRoot, tmp.getRoot());

    // Run the build, setting PWD to the above symlink.  Typically, this causes compilers to use
    // the symlinked directory, even though it's not the right project root.
    BuildTarget target = BuildTargetFactory.newInstance("//:simple#default,static");
    workspace
        .runBuckCommandWithEnvironmentOverridesAndContext(
            tmp.getRoot(),
            Optional.empty(),
            ImmutableMap.of("PWD", symlinkedRoot.toString()),
            "build",
            target.getFullyQualifiedName())
        .assertSuccess();

    // Verify that we still sanitized this path correctly.
    Path lib = workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s/libsimple.a"));
    String contents = Files.asByteSource(lib.toFile()).asCharSource(Charsets.ISO_8859_1).read();
    assertFalse(lib.toString(), contents.contains(tmp.getRoot().toString()));
    assertFalse(lib.toString(), contents.contains(symlinkedRoot.toString()));

    folder.delete();
  }

  @Test
  public void inputBasedRuleKeyAvoidsRerunningIfGeneratedSourceDoesNotChange() throws Exception {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildTarget target =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//:binary_using_generated_source");
    String unusedGenruleInput = "unused.dat";
    BuildTarget genrule = BuildTargetFactory.newInstance("//:gensource");
    String sourceName = "bar.cpp";
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), target, cxxPlatform);
    BuildTarget compileTarget = cxxSourceRuleFactory.createCompileBuildTarget(sourceName);

    // Run the build and verify that the C++ source was compiled.
    workspace.runBuckBuild(target.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget).getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Now modify the unused genrule input.
    workspace.writeContentsToPath("SOMETHING ELSE", unusedGenruleInput);

    // Run the build again and verify that got a matching input-based rule key, and therefore
    // didn't recompile.
    workspace.runBuckBuild(target.toString()).assertSuccess();

    // Verify that the genrule actually re-ran.
    assertThat(
        workspace.getBuildLog().getLogEntry(genrule).getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Verify that the compile rules aren't re-run.
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget).getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY)));
  }

  @Test
  public void inputBasedRuleKeyAvoidsRerunningIfGeneratedHeaderDoesNotChange() throws Exception {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildTarget target = BuildTargetFactory.newInstance("//:binary_using_generated_header");
    String unusedGenruleInput = "unused.dat";
    BuildTarget genrule = BuildTargetFactory.newInstance("//:genheader");
    String sourceName = "foo.cpp";
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), target, cxxPlatform);
    BuildTarget compileTarget = cxxSourceRuleFactory.createCompileBuildTarget(sourceName);

    // Run the build and verify that the C++ source was compiled.
    workspace.runBuckBuild(target.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget).getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Now modify the unused genrule input.
    workspace.writeContentsToPath("SOMETHING ELSE", unusedGenruleInput);

    // Run the build again and verify that got a matching input-based rule key, and therefore
    // didn't recompile.
    workspace.runBuckBuild(target.toString()).assertSuccess();

    // Verify that the genrule actually re-ran.
    assertThat(
        workspace.getBuildLog().getLogEntry(genrule).getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Verify that the compile rules aren't re-run.
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget).getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY)));
  }

  private void depfileBasedRuleKeyRebuildsAfterChangeToUsedHeader(boolean disableHeaderSymlinks)
      throws Exception {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    String targetName =
        "//:binary_with_used_full_header" + (disableHeaderSymlinks ? "_nosymlinks" : "");
    BuildTarget target = BuildTargetFactory.newInstance(targetName);
    String usedHeaderName = "source_full_header.h";
    String sourceName = "source_full_header.cpp";
    BuildTarget preprocessTarget = getPreprocessTarget(cxxPlatform, target, sourceName);

    // Run the build and verify that the C++ source was preprocessed.
    workspace.runBuckBuild("--config", "build.depfiles=enabled", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry firstRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        firstRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Modify the used header.
    workspace.writeContentsToPath("static inline int newFunction() { return 20; }", usedHeaderName);

    // Run the build again and verify that we recompiled as the header caused the depfile rule key
    // to change.
    workspace.runBuckBuild("--config", "build.depfiles=enabled", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry secondRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        secondRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Also, make sure all three rule keys are actually different.
    assertThat(secondRunEntry.getRuleKey(), Matchers.not(equalTo(firstRunEntry.getRuleKey())));
  }

  @Test
  public void depfileBasedRuleKeyRebuildsAfterChangeToUsedHeader() throws Exception {
    depfileBasedRuleKeyRebuildsAfterChangeToUsedHeader(false);
  }

  @Test
  public void depfileBasedRuleKeyRebuildsAfterChangeToUsedHeaderNoSymlinks() throws Exception {
    depfileBasedRuleKeyRebuildsAfterChangeToUsedHeader(true);
  }

  private void depfileBasedRuleKeyRebuildsAfterChangeToUsedHeaderUsingFileRelativeInclusion(
      boolean disableHeaderSymlinks) throws Exception {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    String targetName =
        "//:binary_with_used_relative_header" + (disableHeaderSymlinks ? "_nosymlinks" : "");
    BuildTarget target = BuildTargetFactory.newInstance(targetName);
    String usedHeaderName = "source_relative_header.h";
    String sourceName = "source_relative_header.cpp";
    BuildTarget preprocessTarget = getPreprocessTarget(cxxPlatform, target, sourceName);

    // Run the build and verify that the C++ source was preprocessed.
    workspace.runBuckBuild("--config", "build.depfiles=enabled", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry firstRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        firstRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Modify the used header.
    workspace.writeContentsToPath("static inline int newFunction() { return 20; }", usedHeaderName);

    // Run the build again and verify that we recompiled as the header caused the depfile rule key
    // to change.
    workspace.runBuckBuild("--config", "build.depfiles=enabled", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry secondRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        secondRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Also, make sure all three rule keys are actually different.
    assertThat(secondRunEntry.getRuleKey(), Matchers.not(equalTo(firstRunEntry.getRuleKey())));
  }

  @Test
  public void depfileBasedRuleKeyRebuildsAfterChangeToUsedHeaderUsingFileRelativeInclusion()
      throws Exception {
    depfileBasedRuleKeyRebuildsAfterChangeToUsedHeaderUsingFileRelativeInclusion(false);
  }

  @Test
  public void
      depfileBasedRuleKeyRebuildsAfterChangeToUsedHeaderUsingFileRelativeInclusionNosymlinks()
          throws Exception {
    depfileBasedRuleKeyRebuildsAfterChangeToUsedHeaderUsingFileRelativeInclusion(true);
  }

  private void depfileBasedRuleKeyRebuildsAfterChangeToUsedParentHeaderUsingFileRelativeInclusion(
      boolean disableHeaderSymlinks) throws Exception {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    String targetName =
        "//:binary_with_used_relative_parent_header" + (disableHeaderSymlinks ? "_nosymlinks" : "");
    BuildTarget target = BuildTargetFactory.newInstance(targetName);
    String usedHeaderName = "source_relative_parent_header.h";
    String sourceName = "source_relative_parent_header/source.cpp";
    BuildTarget preprocessTarget = getPreprocessTarget(cxxPlatform, target, sourceName);

    // Run the build and verify that the C++ source was preprocessed.
    workspace.runBuckBuild("--config", "build.depfiles=enabled", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry firstRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        firstRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Modify the used header.
    workspace.writeContentsToPath("static inline int newFunction() { return 20; }", usedHeaderName);

    // Run the build again and verify that we recompiled as the header caused the depfile rule key
    // to change.
    workspace.runBuckBuild("--config", "build.depfiles=enabled", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry secondRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        secondRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Also, make sure all three rule keys are actually different.
    assertThat(secondRunEntry.getRuleKey(), Matchers.not(equalTo(firstRunEntry.getRuleKey())));
  }

  @Test
  public void depfileBasedRuleKeyRebuildsAfterChangeToUsedParentHeaderUsingFileRelativeInclusion()
      throws Exception {
    depfileBasedRuleKeyRebuildsAfterChangeToUsedParentHeaderUsingFileRelativeInclusion(false);
  }

  @Test
  public void
      depfileBasedRuleKeyRebuildsAfterChangeToUsedParentHeaderUsingFileRelativeInclusionNoSymlinks()
          throws Exception {
    depfileBasedRuleKeyRebuildsAfterChangeToUsedParentHeaderUsingFileRelativeInclusion(true);
  }

  private void depfileBasedRuleKeyAvoidsRecompilingAfterChangeToUnusedHeader(
      boolean disableHeaderSymlinks) throws Exception {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    String targetName =
        "//:binary_with_unused_header" + (disableHeaderSymlinks ? "_nosymlinks" : "");
    BuildTarget target = BuildTargetFactory.newInstance(targetName);
    String unusedHeaderName = "unused_header.h";
    String sourceName = "source.cpp";
    BuildTarget preprocessTarget = getPreprocessTarget(cxxPlatform, target, sourceName);

    // Run the build and verify that the C++ source was preprocessed.
    workspace.runBuckBuild("--config", "build.depfiles=enabled", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry firstRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        firstRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Now modify the unused header.
    workspace.writeContentsToPath(
        "static inline int newFunction() { return 20; }", unusedHeaderName);

    // Run the build again and verify that got a matching depfile rule key, and therefore
    // didn't recompile.
    workspace.runBuckBuild("--config", "build.depfiles=enabled", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry secondRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        secondRunEntry.getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY)));

    // Also, make sure the original rule keys are actually different.
    assertThat(secondRunEntry.getRuleKey(), Matchers.not(equalTo(firstRunEntry.getRuleKey())));
  }

  @Test
  public void depfileBasedRuleKeyAvoidsRecompilingAfterChangeToUnusedHeader() throws Exception {
    depfileBasedRuleKeyAvoidsRecompilingAfterChangeToUnusedHeader(false);
  }

  @Test
  public void depfileBasedRuleKeyAvoidsRecompilingAfterChangeToUnusedHeaderNoSymlinks()
      throws Exception {
    depfileBasedRuleKeyAvoidsRecompilingAfterChangeToUnusedHeader(true);
  }

  @Test
  public void manifestCachingRebuildsAfterChangeToUsedHeader() throws Exception {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildTarget target = BuildTargetFactory.newInstance("//:binary_with_used_full_header");
    String usedHeaderName = "source_full_header.h";
    String sourceName = "source_full_header.cpp";
    BuildTarget preprocessTarget = getPreprocessTarget(cxxPlatform, target, sourceName);

    // Enable caching for manifest-based caching.
    workspace.enableDirCache();

    // Run the build and verify that the C++ source was preprocessed.
    workspace.runBuckBuild("--config", "build.depfiles=cache", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry firstRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        firstRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Modify the used header.
    workspace.writeContentsToPath("static inline int newFunction() { return 20; }", usedHeaderName);

    // Clean the build directory, so that we need to go to cache.
    workspace.runBuckCommand("clean", "--keep-cache");

    // Run the build again and verify that we recompiled as the header caused the depfile rule key
    // to change.
    workspace.runBuckBuild("--config", "build.depfiles=cache", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry secondRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        secondRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Also, make sure all three rule keys are actually different.
    assertThat(secondRunEntry.getRuleKey(), Matchers.not(equalTo(firstRunEntry.getRuleKey())));
  }

  @Test
  public void manifestCachingRebuildsAfterChangeToUsedHeaderUsingFileRelativeInclusion()
      throws Exception {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildTarget target = BuildTargetFactory.newInstance("//:binary_with_used_relative_header");
    String usedHeaderName = "source_relative_header.h";
    String sourceName = "source_relative_header.cpp";
    BuildTarget preprocessTarget = getPreprocessTarget(cxxPlatform, target, sourceName);

    // Enable caching for manifest-based caching.
    workspace.enableDirCache();

    // Run the build and verify that the C++ source was preprocessed.
    workspace.runBuckBuild("--config", "build.depfiles=cache", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry firstRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        firstRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Modify the used header.
    workspace.writeContentsToPath("static inline int newFunction() { return 20; }", usedHeaderName);

    // Clean the build directory, so that we need to go to cache.
    workspace.runBuckCommand("clean", "--keep-cache");

    // Run the build again and verify that we recompiled as the header caused the depfile rule key
    // to change.
    workspace.runBuckBuild("--config", "build.depfiles=cache", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry secondRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        secondRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Also, make sure all three rule keys are actually different.
    assertThat(secondRunEntry.getRuleKey(), Matchers.not(equalTo(firstRunEntry.getRuleKey())));
  }

  @Test
  public void manifestCachingGetsHitAfterChangeToUnusedHeader() throws Exception {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildTarget target = BuildTargetFactory.newInstance("//:binary_with_unused_header");
    String unusedHeaderName = "unused_header.h";
    String sourceName = "source.cpp";
    BuildTarget preprocessTarget = getPreprocessTarget(cxxPlatform, target, sourceName);

    // Enable caching for manifest-based caching.
    workspace.enableDirCache();

    // Run the build and verify that the C++ source was preprocessed.
    workspace.runBuckBuild("--config", "build.depfiles=cache", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry firstRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        firstRunEntry.getSuccessType(), equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Clean the build directory, so that we need to go to cache.
    workspace.runBuckCommand("clean", "--keep-cache");

    // Now modify the unused header.
    workspace.writeContentsToPath(
        "static inline int newFunction() { return 20; }", unusedHeaderName);

    // Run the build again and verify that got a matching depfile rule key, and therefore
    // didn't recompile.
    workspace.runBuckBuild("--config", "build.depfiles=cache", target.toString()).assertSuccess();
    BuckBuildLog.BuildLogEntry secondRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        secondRunEntry.getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED)));

    // Also, make sure the original rule keys are actually different.
    assertThat(secondRunEntry.getRuleKey(), Matchers.not(equalTo(firstRunEntry.getRuleKey())));
  }

  @Test
  public void parentDirectoryReferenceInSource() throws IOException {
    Assume.assumeFalse("parent directories do not work in sandbox mode", sandboxSource);
    workspace.writeContentsToPath(
        "[cxx]\n"
            + "  sandbox_sources = "
            + sandboxSource
            + "\n"
            + "[project]\n  check_package_boundary = false\n",
        ".buckconfig");
    workspace.runBuckBuild("//parent_dir_ref:simple#default,static").assertSuccess();
  }

  @Test
  public void langCompilerFlags() throws IOException {
    workspace.runBuckBuild("//:lang_compiler_flags#default,static").assertSuccess();
  }

  @Test
  public void binaryBuildRuleTools() throws IOException {
    workspace
        .runBuckBuild(
            "-c",
            "cxx.cc=//:cc",
            "-c",
            "cxx.cc_type=gcc",
            "-c",
            "cxx.cpp=//:cc",
            "-c",
            "cxx.cpp_type=gcc",
            "-c",
            "cxx.cxx=//:cxx",
            "-c",
            "cxx.cxx_type=gcc",
            "-c",
            "cxx.cxxpp=//:cxx",
            "-c",
            "cxx.cxxpp_type=gcc",
            "//:simple#default,static")
        .assertSuccess();
  }

  @Test
  public void ignoreVerifyHeaders() throws IOException {
    ProcessResult result =
        workspace.runBuckBuild("-c", "cxx.untracked_headers=ignore", "//:untracked_header");
    if (sandboxSource) {
      result.assertFailure();
    } else {
      result.assertSuccess();
    }
  }

  @Test
  public void errorVerifyHeaders() throws IOException {
    ProcessResult result;
    result =
        workspace.runBuckBuild(
            "-c",
            "cxx.untracked_headers=error",
            "-c",
            "cxx.untracked_headers_whitelist=/usr/include/stdc-predef\\.h",
            "//:untracked_header");
    result.assertFailure();
    if (sandboxSource) {
      assertThat(
          result.getStderr(),
          Matchers.anyOf(
              // clang
              containsString("'untracked_header.h' file not found"),
              // gcc
              containsString("untracked_header.h: No such file or directory")));
    } else {
      assertThat(
          result.getStderr(),
          containsString(
              "untracked_header.cpp: included an untracked header \"untracked_header.h\""));
    }
  }

  @Test
  public void whitelistVerifyHeaders() throws IOException {
    ProcessResult result =
        workspace.runBuckBuild(
            "-c",
            "cxx.untracked_headers=error",
            "-c",
            "cxx.untracked_headers_whitelist="
                + "/usr/include/stdc-predef\\.h, /usr/local/.*, untracked_.*.h",
            "//:untracked_header");
    if (sandboxSource) {
      result.assertFailure();
    } else {
      result.assertSuccess();
    }
  }

  private BuildTarget getPreprocessTarget(
      CxxPlatform cxxPlatform, BuildTarget target, String source) {
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), target, cxxPlatform);
    return cxxSourceRuleFactory.createCompileBuildTarget(source);
  }

  @Test
  public void errorVerifyHeadersWithPrefixHeader() throws Exception {
    ProcessResult result =
        workspace.runBuckBuild(
            "-c",
            "cxx.untracked_headers=error",
            "-c",
            "cxx.untracked_headers_whitelist="
                + "/usr/include/stdc-predef\\.h, untracked_header\\.h",
            "//:untracked_header_with_prefix_header");
    result.assertSuccess();
  }

  @Test
  public void verifyAppleFrameworksHeaders() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProcessResult result =
        workspace.runBuckBuild(
            "-c",
            "cxx.untracked_headers=error",
            "-c",
            "cxx.default_platform=iphonesimulator-x86_64",
            "//:with_xctest");
    if (sandboxSource) {
      result.assertFailure();
    } else {
      result.assertSuccess();
    }
  }

  @Test
  public void canCompileSourcesFromOtherCells() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this,
            "cxx_preprocess_and_compile_can_compile_sources_from_other_cells",
            tmp.newFolder());
    workspace.setUp();
    workspace
        .runBuckBuild(
            "//:binary",
            "-c",
            "cxx.untracked_headers=error",
            "-c",
            "cxx.untracked_headers_whitelist=" + "/usr/.*")
        .assertSuccess();
  }

  @Test
  public void languageFlagCanBeOverridden() throws IOException {
    assumeTrue(
        "Windows does not use gcc style language parameters.",
        Platform.detect() != Platform.WINDOWS);
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "override_lang", tmp.newFolder());
    workspace.setUp();
    workspace.runBuckBuild("//:c-as-c").assertFailure();
    workspace.runBuckBuild("//:c-as-cxx").assertSuccess();
  }
}
