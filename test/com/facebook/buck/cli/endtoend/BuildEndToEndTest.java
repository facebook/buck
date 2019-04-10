/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.cli.endtoend;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.doctor.BuildLogHelper;
import com.facebook.buck.doctor.config.BuildLogEntry;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.testutil.PlatformUtils;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.endtoend.EndToEndEnvironment;
import com.facebook.buck.testutil.endtoend.EndToEndRunner;
import com.facebook.buck.testutil.endtoend.EndToEndTestDescriptor;
import com.facebook.buck.testutil.endtoend.EndToEndWorkspace;
import com.facebook.buck.testutil.endtoend.Environment;
import com.facebook.buck.testutil.endtoend.EnvironmentFor;
import com.facebook.buck.testutil.endtoend.ToggleState;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EndToEndRunner.class)
public class BuildEndToEndTest {

  @Environment
  public static EndToEndEnvironment getBaseEnvironment() {
    return new EndToEndEnvironment()
        .withCommand("build")
        .addLocalConfigSet(
            ImmutableMap.of("parser", ImmutableMap.of("default_build_file_syntax", "SKYLARK")))
        .addLocalConfigSet(
            ImmutableMap.of("parser", ImmutableMap.of("default_build_file_syntax", "PYTHON_DSL")));
  }

  @EnvironmentFor(testNames = {"shouldRewriteFailureMessagesAndAppendThem"})
  public static EndToEndEnvironment setTargetPathThatCallsFail() {
    return getBaseEnvironment()
        .addTemplates("cli")
        .withTargets("//parse_failure/fail_message:fail");
  }

  @EnvironmentFor(testNames = {"shouldRewriteFailureMessagesForInvalidTargets"})
  public static EndToEndEnvironment setTargetPathThatHasBadTargets() {
    return getBaseEnvironment()
        .addTemplates("cli")
        .withTargets("//parse_failure/invalid_deps:main");
  }

  @EnvironmentFor(testNames = {"testMissingTargetLocationIsShowAfterRebuild"})
  public static EndToEndEnvironment setTargetPathWithMissingDep() {
    return getBaseEnvironment()
        .addTemplates("missing_dep")
        .withTargets("//:a")
        .withBuckdToggled(ToggleState.ON);
  }

  @EnvironmentFor(testNames = {"changingVersionShouldClearBuckOutWithConfiguredBuckOutDir"})
  public static EndToEndEnvironment setTargetPathWithBinaryBuiltFromGenrule() {
    return getBaseEnvironment()
        .addTemplates("cxx_dependent_on_py")
        .withBuckdToggled(ToggleState.ON);
  }

  @EnvironmentFor(testNames = {"printsErrorWhenBuckConfigIsMissing"})
  public static EndToEndEnvironment setSimpleEnv() {
    return getBaseEnvironment().addTemplates("cli");
  }

  @EnvironmentFor(testNames = {"nestedBuildsUseDifferentUUID"})
  public static EndToEndEnvironment setupNestedBuildsEnv() {
    return getBaseEnvironment().addTemplates("nested_build");
  }

  @Test
  public void shouldRewriteFailureMessagesAndAppendThem(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    workspace.addBuckConfigLocalOption(
        "ui",
        "error_message_augmentations",
        "\"name ([-\\\\w]*) provided\" => \"You pizza'd when you should have french fried on $1\"");

    Pattern expected =
        Pattern.compile(
            "Invalid name fail-py provided$.*^You pizza'd when you should have french fried on fail-py",
            Pattern.MULTILINE | Pattern.DOTALL);

    ProcessResult result = workspace.runBuckCommand(test);
    result.assertExitCode(ExitCode.PARSE_ERROR);
    Assert.assertTrue(
        String.format("'%s' was not contained in '%s'", expected.pattern(), result.getStderr()),
        expected.matcher(result.getStderr()).find());
  }

  @Test
  public void shouldRewriteFailureMessagesForInvalidTargets(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    workspace.addBuckConfigLocalOption(
        "ui",
        "error_message_augmentations",
        "\"The rule (//\\\\S+)-cxx could not be found.\" => \"Please make sure that $1 "
            + "is a cxx library. If it is not, add it to extra_deps instead\"");

    Pattern expected =
        Pattern.compile(
            "The rule //parse_failure/invalid_deps:main-cxx could not be found\\..*"
                + "Please make sure that //parse_failure/invalid_deps:main is a cxx library. "
                + "If it is not, add it to extra_deps instead",
            Pattern.MULTILINE | Pattern.DOTALL);

    ProcessResult result = workspace.runBuckCommand(test);
    result.assertFailure();
    Assert.assertTrue(
        String.format("'%s' was not contained in '%s'", expected.pattern(), result.getStderr()),
        expected.matcher(result.getStderr()).find());
  }

  @Test
  public void testMissingTargetLocationIsShowAfterRebuild(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    ProcessResult result = workspace.runBuckCommand(test);
    result.assertFailure();
    assertThat(
        result.getStderr(),
        containsString(
            "No build file at missing/BUCK when resolving target //missing:dep.\n"
                + "\n"
                + "This error happened while trying to get dependency '//missing:dep' of target '//:a'"));
    result = workspace.runBuckCommand(test);
    result.assertFailure();
    assertThat(
        result.getStderr(),
        containsString(
            "No build file at missing/BUCK when resolving target //missing:dep.\n"
                + "\n"
                + "This error happened while trying to get dependency '//missing:dep' of target '//:a'"));
  }

  @Test
  public void changingVersionShouldClearBuckOutWithConfiguredBuckOutDir(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Throwable {

    for (String template : test.getTemplateSet()) {
      workspace.addPremadeTemplate(template);
    }

    ProcessResult result = workspace.runBuckCommand("run", "@mode/opt", "//main_bin:main_bin");
    result.assertSuccess();

    result = workspace.runBuckCommand("run", "@mode/dev", "//main_bin:main_bin");
    result.assertSuccess();

    Path optVersion =
        workspace.getDestPath().resolve(Paths.get("buck-out", "opt", ".currentversion"));
    Path devVersion =
        workspace.getDestPath().resolve(Paths.get("buck-out", "dev", ".currentversion"));
    Path optBin =
        workspace
            .getDestPath()
            .resolve(Paths.get("buck-out", "opt", "gen", "main_bin", "main_bin"));
    Path devBin =
        workspace
            .getDestPath()
            .resolve(Paths.get("buck-out", "dev", "gen", "main_bin", "main_bin"));

    Assert.assertTrue(Files.exists(optVersion));
    Assert.assertTrue(Files.exists(devVersion));
    Assert.assertTrue(Files.exists(optBin));
    Assert.assertTrue(Files.exists(devBin));

    Files.delete(optBin);
    Files.delete(devBin);

    result = workspace.runBuckCommand("run", "@mode/opt", "//main_bin:main_bin");
    result.assertFailure();

    result = workspace.runBuckCommand("run", "@mode/dev", "//main_bin:main_bin");
    result.assertFailure();

    Files.delete(optVersion);
    Files.delete(devVersion);

    result = workspace.runBuckCommand("run", "@mode/dev", "//main_bin:main_bin");
    result.assertSuccess();

    result = workspace.runBuckCommand("run", "@mode/opt", "//main_bin:main_bin");
    result.assertSuccess();
  }

  @Test
  public void printsErrorWhenBuckConfigIsMissing(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Throwable {
    workspace.setup();

    String[] expected =
        new String[] {
          "This does not appear to be the root of a Buck project. Please 'cd'",
          "to the root of your project before running buck. If this really is",
          "the root of your project, run",
          "'touch .buckconfig'",
          "and then re-run your buck command."
        };

    ProcessResult result = workspace.runBuckCommand("query", "//:");
    result.assertExitCode(ExitCode.COMMANDLINE_ERROR);
    for (String line : expected) {
      assertThat(result.getStderr(), containsString(line));
    }
    assertThat(result.getStderr(), not(containsString("NoBuckConfigFoundException")));
  }

  @Test
  public void nestedBuildsUseDifferentUUID(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Throwable {
    workspace.setup();

    ImmutableList<String> fullBuckCommand =
        PlatformUtils.getForPlatform().getBuckCommandBuilder().build();
    String buckCommand = fullBuckCommand.get(fullBuckCommand.size() - 1);

    workspace
        .runBuckCommand(
            false,
            ImmutableMap.of("BUCK_BUILD_ID", "1234-5678"),
            test.getTemplateSet(),
            "build",
            "-c",
            "user.buck_path=" + buckCommand,
            "//:query")
        .assertSuccess();

    ImmutableList<BuildLogEntry> helper =
        new BuildLogHelper(
                new DefaultProjectFilesystemFactory()
                    .createProjectFilesystem(workspace.getDestPath()))
            .getBuildLogs();

    assertEquals(2, helper.size());
    Optional<BuildLogEntry> buildCommand =
        helper.stream()
            .filter(
                log -> {
                  Optional<List<String>> args = log.getCommandArgs();
                  return args.isPresent()
                      && args.get().containsAll(ImmutableList.of("build", "//:query"));
                })
            .findFirst();
    Optional<BuildLogEntry> queryCommand =
        helper.stream()
            .filter(
                log -> {
                  Optional<List<String>> args = log.getCommandArgs();
                  return args.isPresent()
                      && args.get().containsAll(ImmutableList.of("query", "//:query"));
                })
            .findFirst();
    Assert.assertTrue("Build command was not found in logs", buildCommand.isPresent());
    Assert.assertTrue("Query command was not found in logs", queryCommand.isPresent());
    assertEquals(Optional.of(new BuildId("1234-5678")), buildCommand.get().getBuildId());
    Assert.assertNotEquals(Optional.of(new BuildId("1234-5678")), queryCommand.get().getBuildId());
  }
}
