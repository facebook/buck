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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.martiansoftware.nailgun.NGContext;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

@RunWith(Parameterized.class)
public class CxxPreprocessAndCompileIntegrationTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(
        new Object[] {CxxPreprocessMode.COMBINED},
        new Object[] {CxxPreprocessMode.SEPARATE},
        new Object[] {CxxPreprocessMode.PIPED});
  }

  @Parameterized.Parameter
  public CxxPreprocessMode mode;

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "step_test", tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[cxx]\n" +
        "  preprocess_mode = " + mode.toString().toLowerCase() + "\n" +
        "  cppflags = -g\n" +
        "  cflags = -g\n" +
        "  cxxppflags = -g\n" +
        "  cxxflags = -g\n" +
        "[build]\n" +
        "  depfiles = false\n",
        ".buckconfig");
  }

  @Test
  public void sanitizeWorkingDirectory() throws IOException {

    // TODO(user): Currently, we don't properly sanitize the working directory for the default
    // platform when using the clang compiler.
    assumeNotUsingSeparateOrPipedModesWithClang();

    workspace.runBuckBuild("//:simple#default,static").assertSuccess();
    Path lib = workspace.getPath("buck-out/gen/simple#default,static/libsimple.a");
    String contents =
        Files.asByteSource(lib.toFile())
            .asCharSource(Charsets.ISO_8859_1)
            .read();
    assertFalse(lib.toString(), contents.contains(tmp.getRootPath().toString()));
  }

  @Test
  public void sanitizeSymlinkedWorkingDirectory() throws IOException {

    // TODO(user): Currently, we don't properly sanitize the working directory for the default
    // platform when using the clang compiler.
    assumeNotUsingSeparateOrPipedModesWithClang();

    TemporaryFolder folder = new TemporaryFolder();
    folder.create();

    // Setup up a symlink to our working directory.
    Path symlinkedRoot = folder.getRoot().toPath().resolve("symlinked-root");
    java.nio.file.Files.createSymbolicLink(symlinkedRoot, tmp.getRootPath());

    // Run the build, setting PWD to the above symlink.  Typically, this causes compilers to use
    // the symlinked directory, even though it's not the right project root.
    Map<String, String> envCopy = Maps.newHashMap(System.getenv());
    envCopy.put("PWD", symlinkedRoot.toString());
    workspace.runBuckCommandWithEnvironmentAndContext(
        tmp.getRootPath(),
        Optional.<NGContext>absent(),
        Optional.<BuckEventListener>absent(),
        Optional.of(ImmutableMap.copyOf(envCopy)),
        "build",
        "//:simple#default,static")
            .assertSuccess();

    // Verify that we still sanitized this path correctly.
    Path lib = workspace.getPath("buck-out/gen/simple#default,static/libsimple.a");
    String contents =
        Files.asByteSource(lib.toFile())
            .asCharSource(Charsets.ISO_8859_1)
            .read();
    assertFalse(lib.toString(), contents.contains(tmp.getRootPath().toString()));
    assertFalse(lib.toString(), contents.contains(symlinkedRoot.toString()));

    folder.delete();
  }

  @Test
  public void inputBasedRuleKeyAvoidsRerunningIfGeneratedSourceDoesNotChange() throws Exception {
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new CxxBuckConfig(new FakeBuckConfig()));
    BuildTarget target = BuildTargetFactory.newInstance("//:binary_using_generated_source");
    String unusedGenruleInput = "unused.dat";
    BuildTarget genrule = BuildTargetFactory.newInstance("//:gensource");
    String sourceName = "bar.cpp";
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(target, cxxPlatform);
    BuildTarget preprocessTarget =
        cxxSourceRuleFactory.createPreprocessBuildTarget(
            sourceName,
            AbstractCxxSource.Type.CXX,
            CxxSourceRuleFactory.PicType.PDC);
    BuildTarget compileTarget =
        cxxSourceRuleFactory.createCompileBuildTarget(
            sourceName,
            CxxSourceRuleFactory.PicType.PDC);

    // Run the build and verify that the C++ source was (preprocessed and) compiled.
    workspace.runBuckBuild(target.toString()).assertSuccess();
    if (mode == CxxPreprocessMode.SEPARATE) {
      assertThat(
          workspace.getBuildLog().getLogEntry(preprocessTarget).getSuccessType(),
          equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));
    }
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget).getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Now modify the unused genrule input.
    workspace.writeContentsToPath(
        "SOMETHING ELSE",
        unusedGenruleInput);

    // Run the build again and verify that got a matching input-based rule key, and therefore
    // didn't recompile.
    workspace.runBuckBuild(target.toString()).assertSuccess();

    // Verify that the genrule actually re-ran.
    assertThat(
        workspace.getBuildLog().getLogEntry(genrule).getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Verify that the (preprocess and) compile rules aren't re-run.
    if (mode == CxxPreprocessMode.SEPARATE) {
      assertThat(
          workspace.getBuildLog().getLogEntry(preprocessTarget).getSuccessType(),
          equalTo(Optional.of(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY)));
    }
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget).getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY)));
  }

  @Test
  public void inputBasedRuleKeyAvoidsRerunningIfGeneratedHeaderDoesNotChange() throws Exception {
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new CxxBuckConfig(new FakeBuckConfig()));
    BuildTarget target = BuildTargetFactory.newInstance("//:binary_using_generated_header");
    String unusedGenruleInput = "unused.dat";
    BuildTarget genrule = BuildTargetFactory.newInstance("//:genheader");
    String sourceName = "foo.cpp";
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(target, cxxPlatform);
    BuildTarget preprocessTarget =
        cxxSourceRuleFactory.createPreprocessBuildTarget(
            sourceName,
            AbstractCxxSource.Type.CXX,
            CxxSourceRuleFactory.PicType.PDC);
    BuildTarget compileTarget =
        cxxSourceRuleFactory.createCompileBuildTarget(
            sourceName,
            CxxSourceRuleFactory.PicType.PDC);

    // Run the build and verify that the C++ source was (preprocessed and) compiled.
    workspace.runBuckBuild(target.toString()).assertSuccess();
    if (mode == CxxPreprocessMode.SEPARATE) {
      assertThat(
          workspace.getBuildLog().getLogEntry(preprocessTarget).getSuccessType(),
          equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));
    }
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget).getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Now modify the unused genrule input.
    workspace.writeContentsToPath(
        "SOMETHING ELSE",
        unusedGenruleInput);

    // Run the build again and verify that got a matching input-based rule key, and therefore
    // didn't recompile.
    workspace.runBuckBuild(target.toString()).assertSuccess();

    // Verify that the genrule actually re-ran.
    assertThat(
        workspace.getBuildLog().getLogEntry(genrule).getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Verify that the (preprocess and) compile rules aren't re-run.
    if (mode == CxxPreprocessMode.SEPARATE) {
      assertThat(
          workspace.getBuildLog().getLogEntry(preprocessTarget).getSuccessType(),
          equalTo(Optional.of(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY)));
    }
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget).getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY)));
  }

  @Test
  public void inputBasedRuleKeyAvoidsRecompilingAfterChangeToUnusedHeader() throws Exception {

    // This test is only meant to check the separate flow, as we want to avoid recompiling if only
    // unused headers have changed.
    assumeTrue(
        "only tests \"separate\" preprocess mode",
        mode == CxxPreprocessMode.SEPARATE);

    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new CxxBuckConfig(new FakeBuckConfig()));
    BuildTarget target = BuildTargetFactory.newInstance("//:binary_with_unused_header");
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(target, cxxPlatform);
    String unusedHeaderName = "unused_header.h";
    String sourceName = "source.cpp";
    BuildTarget compileTarget =
        cxxSourceRuleFactory.createCompileBuildTarget(
            sourceName,
            CxxSourceRuleFactory.PicType.PDC);

    // Run the build and verify that the C++ source was compiled.
    workspace.runBuckBuild(target.toString());
    BuckBuildLog.BuildLogEntry firstRunEntry = workspace.getBuildLog().getLogEntry(compileTarget);
    assertThat(
        firstRunEntry.getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Now modify the unused header.
    workspace.writeContentsToPath(
        "static inline int newFunction() { return 20; }",
        unusedHeaderName);

    // Run the build again and verify that got a matching input-based rule key, and therefore
    // didn't recompile.
    workspace.runBuckBuild(target.toString());
    BuckBuildLog.BuildLogEntry secondRunEntry = workspace.getBuildLog().getLogEntry(compileTarget);
    assertThat(
        secondRunEntry.getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY)));


    // Also, make sure the original rule keys are actually different.
    assertThat(
        secondRunEntry.getRuleKey(),
        Matchers.not(equalTo(firstRunEntry.getRuleKey())));
  }

  @Test
  public void depfileBasedRuleKeyAvoidsRecompilingAfterChangeToUnusedHeader() throws Exception {
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new CxxBuckConfig(new FakeBuckConfig()));
    BuildTarget target = BuildTargetFactory.newInstance("//:source_relative_header");
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(target, cxxPlatform);
    String usedHeaderName = "source_relative_header.h";
    String unusedHeaderName = "unused_header.h";
    String sourceName = "source_relative_header.cpp";
    BuildTarget preprocessTarget;
    if (mode == CxxPreprocessMode.SEPARATE) {
      preprocessTarget = cxxSourceRuleFactory.createPreprocessBuildTarget(
          sourceName,
          AbstractCxxSource.Type.CXX,
          CxxSourceRuleFactory.PicType.PDC);
    } else {
      preprocessTarget = cxxSourceRuleFactory.createCompileBuildTarget(
          sourceName,
          CxxSourceRuleFactory.PicType.PDC);
    }

    // Run the build and verify that the C++ source was preprocessed.
    workspace.runBuckBuild("--config", "build.depfiles=true", target.toString());
    BuckBuildLog.BuildLogEntry firstRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        firstRunEntry.getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    // Now modify the unused header.
    workspace.writeContentsToPath(
        "static inline int newFunction() { return 20; }",
        unusedHeaderName);

    // Run the build again and verify that got a matching depfile rule key, and therefore
    // didn't recompile.
    workspace.runBuckBuild("--config", "build.depfiles=true", target.toString());
    BuckBuildLog.BuildLogEntry secondRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        secondRunEntry.getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY)));


    // Also, make sure the original rule keys are actually different.
    assertThat(
        secondRunEntry.getRuleKey(),
        Matchers.not(equalTo(firstRunEntry.getRuleKey())));

    workspace.writeContentsToPath(
        "static inline int newFunction() { return 20; }",
        usedHeaderName);

    // Run the build again and verify that we recompiled as the header caused the depfile rule key
    // to change.
    workspace.runBuckBuild("--config", "build.depfiles=true", target.toString());
    BuckBuildLog.BuildLogEntry thirdRunEntry =
        workspace.getBuildLog().getLogEntry(preprocessTarget);
    assertThat(
        thirdRunEntry.getSuccessType(),
        equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));


    // Also, make sure all three rule keys are actually different.
    assertThat(
        thirdRunEntry.getRuleKey(),
        Matchers.not(equalTo(firstRunEntry.getRuleKey())));
    assertThat(
        thirdRunEntry.getRuleKey(),
        Matchers.not(equalTo(secondRunEntry.getRuleKey())));
  }

  @Test
  public void parentDirectoryReferenceInSource() throws IOException {
    MorePaths.append(
        workspace.getPath(".buckconfig"),
        "\n[project]\n  check_package_boundary = false\n",
        UTF_8);
    workspace.runBuckBuild("//parent_dir_ref:simple#default,static").assertSuccess();
  }

  public void assumeNotUsingSeparateOrPipedModesWithClang() {
    assumeTrue(
        Platform.detect() != Platform.MACOS ||
            !ImmutableSet.of(CxxPreprocessMode.SEPARATE, CxxPreprocessMode.PIPED).contains(mode));
  }

}
