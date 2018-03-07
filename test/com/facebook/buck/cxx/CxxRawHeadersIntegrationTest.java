/*
 * Copyright 2017-present Facebook, Inc.
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
import static org.junit.Assert.assertThat;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CxxRawHeadersIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private BuildTarget compileTarget1;
  private BuildTarget target1;

  private BuildTarget compileTarget2;
  private BuildTarget target2;

  @Parameterized.Parameter(value = 0)
  public boolean useBuckd;

  @Parameterized.Parameters(name = "useBuckd={0}")
  public static Collection<Object[]> data() {
    return ParameterizedTests.getPermutations(ImmutableList.of(false, true));
  }

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "raw_headers", tmp);
    workspace.setUp();
    String posix_config =
        "[cxx]\n"
            + "  untracked_headers=error\n"
            + "  untracked_headers_whitelist=/usr/include/.*\n"
            + "  cppflags = -Wall -Werror\n"
            + "  cxxppflags = -Wall -Werror\n"
            + "  cflags = -Wall -Werror\n"
            + "  cxxflags = -Wall -Werror\n";
    String windows_config =
        "[cxx]\n"
            + "  untracked_headers=error\n"
            + "  untracked_headers_whitelist=\"C:/Program Files (x86)/.*\", \"c:/program files (x86)/.*\"\n"
            + "  cc=\"C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/bin/amd64/cl.exe\"\n"
            + "  cc_type=windows\n"
            + "  cpp=\"C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/bin/amd64/cl.exe\"\n"
            + "  cpp_type=windows\n"
            + "  cxx=\"C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/bin/amd64/cl.exe\"\n"
            + "  cxx_type=windows\n"
            + "  cxxpp=\"C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/bin/amd64/cl.exe\"\n"
            + "  cxxpp_type=windows\n"
            + "  ld=\"C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/bin/amd64/link.exe\"\n"
            + "  linker_platform=windows\n"
            + "  ar=\"C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/bin/amd64/lib.exe\"\n"
            + "  archiver_platform=windows\n"
            + "  ranlib=\"C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/bin/amd64/lib.exe\"\n";
    String config = Platform.detect() == Platform.WINDOWS ? windows_config : posix_config;
    workspace.writeContentsToPath(config, ".buckconfig");

    String source = "test.cpp";

    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    target1 = BuildTargetFactory.newInstance(workspace.getDestPath(), "//depfiles1:test");
    target2 = BuildTargetFactory.newInstance(workspace.getDestPath(), "//depfiles2/test:test");

    CxxSourceRuleFactory cxxSourceRuleFactory1 =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), target1, cxxPlatform);
    CxxSourceRuleFactory cxxSourceRuleFactory2 =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), target2, cxxPlatform);

    runCommand("build", "//depfiles1:test").assertSuccess();
    compileTarget1 = cxxSourceRuleFactory1.createCompileBuildTarget(source);
    workspace.getBuildLog().assertTargetBuiltLocally(compileTarget1.toString());

    compileTarget2 = cxxSourceRuleFactory2.createCompileBuildTarget(source);
    runCommand("build", "//depfiles2/test:test").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(compileTarget2.toString());
  }

  @Test
  public void listedRawHeadersPassVerification() throws IOException {
    ProcessResult result = runCommand("build", "//lib1");
    result.assertSuccess();
  }

  @Test
  public void listedRawHeadersInDepPassVerification() throws IOException {
    ProcessResult result = runCommand("build", "//lib4");
    result.assertSuccess();
  }

  @Test
  public void unlistedRawHeadersDoNotPassVerification() throws IOException {
    ProcessResult result = runCommand("build", "//lib2");
    result.assertFailure();
    assertThat(result.getStderr(), containsString("included an untracked header"));
  }

  @Test
  public void modifyingUsedHeaderCausesRebuild1() throws IOException {
    workspace.writeContentsToPath("#define SOMETHING", "depfiles1/used.h");
    runCommand("build", target1.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget1).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));
  }

  @Test
  public void modifyingUsedHeaderCausesRebuild2() throws IOException {
    workspace.writeContentsToPath("#define SOMETHING", "depfiles2/headers/used.h");
    runCommand("build", target2.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget2).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));
  }

  @Test
  public void modifyingUnusedHeaderDoesNotCauseRebuild1() throws IOException {
    workspace.writeContentsToPath("#define SOMETHING", "depfiles1/unused.h");
    runCommand("build", target1.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget1).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY)));
  }

  @Test
  public void modifyingUnusedHeaderDoesNotCauseRebuild2() throws IOException {
    workspace.writeContentsToPath("#define SOMETHING", "depfiles2/headers/unused.h");
    runCommand("build", target2.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget2).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY)));
  }

  @Test
  public void modifyingOriginalSourceCausesRebuild1() throws IOException {
    workspace.resetBuildLogFile();
    workspace.writeContentsToPath("int main() { return 1; }", "depfiles1/test.cpp");
    runCommand("build", target1.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget1).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    workspace.resetBuildLogFile();
    workspace.writeContentsToPath("   int main() { return 1; }", "depfiles1/test.cpp");
    runCommand("build", target1.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget1).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    workspace.resetBuildLogFile();
    workspace.writeContentsToPath("int main() { return 2; }", "depfiles1/test.cpp");
    runCommand("build", target1.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget1).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));
  }

  @Test
  public void modifyingOriginalSourceCausesRebuild2() throws IOException {
    workspace.resetBuildLogFile();
    workspace.writeContentsToPath("int main() { return 1; }", "depfiles2/test/test.cpp");
    runCommand("build", target2.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget2).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    workspace.resetBuildLogFile();
    workspace.writeContentsToPath("   int main() { return 1; }", "depfiles2/test/test.cpp");
    runCommand("build", target2.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget2).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    workspace.resetBuildLogFile();
    workspace.writeContentsToPath("int main() { return 2; }", "depfiles2/test/test.cpp");
    runCommand("build", target2.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget2).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));
  }

  @Test
  public void removingUsedHeaderAndReferenceToItCausesRebuild1() throws IOException {
    workspace.writeContentsToPath("int main() { return 1; }", "depfiles1/test.cpp");
    Files.delete(workspace.getPath("depfiles1/used.h"));
    workspace.replaceFileContents("depfiles1/BUCK", "\"used.h\",", "");
    runCommand("build", target1.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget1).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));
  }

  @Test
  public void removingUsedHeaderAndReferenceToItCausesRebuild2() throws IOException {
    workspace.writeContentsToPath("int main() { return 1; }", "depfiles2/test/test.cpp");
    Files.delete(workspace.getPath("depfiles2/headers/used.h"));
    workspace.replaceFileContents("depfiles2/headers/BUCK", "\"used.h\",", "");
    runCommand("build", target2.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(compileTarget2).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));
  }

  @Test
  public void headersUsage() {
    Exception caughtException = null;
    try {
      runCommand("targets", "//lib5:lib5");
    } catch (Exception e) {
      caughtException = e;
    }
    Assert.assertTrue(caughtException != null);
    assertThat(
        caughtException.getMessage(),
        containsString("Cannot use `headers` and `raw_headers` in the same rule"));
  }

  private ProcessResult runCommand(String... args) throws IOException {
    if (useBuckd) {
      return workspace.runBuckdCommand(args);
    } else {
      return workspace.runBuckCommand(args);
    }
  }
}
