/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli.endtoend;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.endtoend.EndToEndEnvironment;
import com.facebook.buck.testutil.endtoend.EndToEndRunner;
import com.facebook.buck.testutil.endtoend.EndToEndTestDescriptor;
import com.facebook.buck.testutil.endtoend.EndToEndWorkspace;
import com.facebook.buck.testutil.endtoend.Environment;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EndToEndRunner.class)
public class FixEndToEndTest {
  private static final Pattern JASABI_REGEX =
      Pattern.compile(
          ".*^jasabi_fix:.*(legacy_fix_script|autofix_source_only_abi_warnings.py)$.*",
          Pattern.DOTALL | Pattern.MULTILINE);

  @Environment
  public static EndToEndEnvironment getBaseEnvironment() {
    return new EndToEndEnvironment().withCommand("run").addTemplates("fix");
  }

  private Path findPython() {
    // Not all test environments have properly registered python3 as the .py handler. So,
    // make sure that we run our fix script with a python3 that we find on the path
    return new ExecutableFinder()
        .getExecutable(Paths.get("python"), EnvVariablesProvider.getSystemEnv())
        .toAbsolutePath();
  }

  private String fixPyCommand() {
    String command = String.format("{repository_root}/fix.py --build-details {fix_spec_path}");
    if (Platform.detect() == Platform.WINDOWS) {
      command = String.format("%s %s", findPython(), command);
    }
    return command;
  }

  private ProcessResult buckCommand(
      EndToEndWorkspace workspace, EndToEndTestDescriptor test, String... command)
      throws Exception {
    return workspace.runBuckCommand(
        false, ImmutableMap.copyOf(test.getVariableMap()), test.getTemplateSet(), command);
  }

  @Test
  public void shouldRunLegacyFixScript(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Throwable {
    // Windows cannot execute always execute .py files directly
    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    // Make sure we get '0' back in the normal case, but also make sure that we error in a
    // place where the script should fail

    DefaultProjectFilesystem fs =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    fs.mkdirs(
        Paths.get(
            "buck-out",
            "log",
            "2019-05-21_15h18m53s_buildcommand_2ef9b523-fc4e-48a3-aa9f-baab9ca36386"));
    fs.createSymLink(
        Paths.get("buck-out", "log", "last_buildcommand"),
        Paths.get("2019-05-21_15h18m53s_buildcommand_2ef9b523-fc4e-48a3-aa9f-baab9ca36386"),
        false);

    buckCommand(workspace, test, "fix").assertSuccess();

    workspace.deleteRecursivelyIfExists(
        workspace.resolve("buck-out").resolve("log").resolve("last_buildcommand").toString());
    ProcessResult failureResult = buckCommand(workspace, test, "fix");
    assertThat(
        failureResult.getStderr(),
        Matchers.matchesPattern(
            Pattern.compile(".*No such file or directory:.*last_buildcommand.*", Pattern.DOTALL)));
  }

  @Test
  public void shouldReturnFixErrorIfScriptReturnsNonZero(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand() + " --exit-code 1",
                "fix_script_contact",
                "buck@example.com",
                "fix_script_message",
                "Running {command}; contact {contact}")));
    ProcessResult result = buckCommand(workspace, test, "fix");

    result.assertExitCode(ExitCode.FIX_FAILED);
    assertThat(
        result.getStderr(),
        Matchers.containsString("build_id: 2ef9b523-fc4e-48a3-aa9f-baab9ca36386"));
    assertThat(result.getStderr(), Matchers.matchesPattern(JASABI_REGEX));
    assertThat(result.getStderr(), Matchers.containsString("manually_invoked: True"));
  }

  @Test
  public void shouldRunForASpecificBuildIfProvidedAndConfigured(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand(),
                "fix_script_contact",
                "buck@example.com",
                "fix_script_message",
                "Running {command}; contact {contact}")));
    ProcessResult result =
        buckCommand(workspace, test, "fix", "--build-id", "2ef9b523-fc4e-48a3-aa9f-baab9ca36386");

    result.assertExitCode(ExitCode.SUCCESS);
    assertThat(
        result.getStderr(),
        Matchers.containsString("build_id: 2ef9b523-fc4e-48a3-aa9f-baab9ca36386"));
    assertThat(result.getStderr(), Matchers.matchesPattern(JASABI_REGEX));
    assertThat(result.getStderr(), Matchers.containsString("manually_invoked: True"));
  }

  @Test
  public void shouldRunForLastBuildIfNoIdProvidedAndScriptConfigured(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand(),
                "fix_script_contact",
                "buck@example.com",
                "fix_script_message",
                "Running {command}; contact {contact}")));
    ProcessResult result = buckCommand(workspace, test, "fix");

    result.assertExitCode(ExitCode.SUCCESS);
    assertThat(
        result.getStderr(),
        Matchers.containsString("build_id: 2ef9b523-fc4e-48a3-aa9f-baab9ca36386"));
    assertThat(result.getStderr(), Matchers.matchesPattern(JASABI_REGEX));
    assertThat(result.getStderr(), Matchers.containsString("manually_invoked: True"));
  }

  @Test
  public void shouldFailIfContactIsNotSet(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Exception {
    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand(),
                "fix_script_message",
                "Running {command}; contact {contact}")));
    ProcessResult result = buckCommand(workspace, test, "fix").assertFailure();

    assertThat(
        result.getStderr(),
        Matchers.containsString("`buck fix` requires a contact for any configured scripts"));
  }

  @Test
  public void shouldFailIfSpecifiedBuildIdDoesNotExist(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand(),
                "fix_script_contact",
                "buck@example.com",
                "fix_script_message",
                "Running {command}; contact {contact}")));
    ProcessResult result =
        buckCommand(workspace, test, "fix", "--build-id", "1234-5678").assertFailure();

    assertThat(
        result.getStderr(), Matchers.containsString("Error fetching logs for build 1234-5678"));
  }

  @Test
  public void shouldFailIfNoLogsExist(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Throwable {
    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand(),
                "fix_script_contact",
                "buck@example.com",
                "fix_script_message",
                "Running {command}; contact {contact}")));

    for (String template : test.getTemplateSet()) {
      workspace.addPremadeTemplate(template);
    }

    workspace.deleteRecursivelyIfExists(
        workspace
            .resolve("buck-out")
            .resolve("log")
            .resolve("2019-05-21_15h18m53s_buildcommand_2ef9b523-fc4e-48a3-aa9f-baab9ca36386")
            .toString());
    ProcessResult result = buckCommand(workspace, test, "fix").assertFailure();

    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "Error finding a valid previous run to get 'fix' information from"));
  }

  @Test
  public void shouldRunScriptInteractively(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Exception {
    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand() + " --wait",
                "fix_script_contact",
                "buck@example.com",
                "fix_script_message",
                "Running {command}; contact {contact}")));
    ProcessResult result =
        workspace.runBuckCommand(
            false,
            ImmutableMap.copyOf(test.getVariableMap()),
            Optional.of("foobar"),
            test.getTemplateSet(),
            "fix");

    result.assertExitCode(ExitCode.SUCCESS);
    assertThat(
        result.getStderr(),
        Matchers.containsString("build_id: 2ef9b523-fc4e-48a3-aa9f-baab9ca36386"));
    assertThat(result.getStderr(), Matchers.matchesPattern(JASABI_REGEX));
    assertThat(result.getStderr(), Matchers.containsString("manually_invoked: True"));
    assertThat(result.getStderr(), Matchers.containsString("user entered foobar"));
  }

  @Test
  public void doesNotRunFixScriptAutomaticallyByDefault(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand(),
                "fix_script_contact",
                "buck@example.com",
                "fix_script_message",
                "Running {command}; contact {contact}")));
    ProcessResult result = buckCommand(workspace, test, "build", "//:foo");

    result.assertExitCode(ExitCode.PARSE_ERROR);
    assertThat(
        result.getStderr(),
        Matchers.not(
            Matchers.containsString(
                "Running `buck fix`. Invoke this manually with `buck fix --build-id ")));
  }

  @Test
  public void shouldNotRunScriptAutomaticallyOnCommandFailureIfNotEnabled(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {

    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand(),
                "fix_script_contact",
                "buck@example.com",
                "fix_script_message",
                "Running {command}; contact {contact}",
                "autofix_enabled",
                "NEVER",
                "autofix_commands",
                "build")));

    ProcessResult result = buckCommand(workspace, test, "build", "//:foo");

    result.assertExitCode(ExitCode.PARSE_ERROR);
    assertThat(
        result.getStderr(),
        Matchers.not(
            Matchers.containsString(
                "Running `buck fix`. Invoke this manually with `buck fix --build-id ")));
  }

  @Test
  public void shouldNotRunScriptAutomaticallyOnCommandFailureIfNotEnabledForCommand(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {

    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand(),
                "fix_script_contact",
                "buck@example.com",
                "fix_script_message",
                "Running {command}; contact {contact}",
                "autofix_enabled",
                "ALWAYS",
                "autofix_commands",
                "test")));
    ProcessResult result = buckCommand(workspace, test, "build", "//:foo");

    result.assertExitCode(ExitCode.PARSE_ERROR);
    assertThat(
        result.getStderr(),
        Matchers.not(
            Matchers.containsString(
                "Running `buck fix`. Invoke this manually with `buck fix --build-id ")));
  }

  @Test
  public void shouldPrintErrorWhenRunAutomaticallyIfFixIsMisconfigured(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {

    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand(),
                "fix_script_message",
                "Running {command}; contact {contact}",
                "autofix_enabled",
                "ALWAYS",
                "autofix_commands",
                "build")));
    ProcessResult result = buckCommand(workspace, test, "build", "//:foo");

    result.assertExitCode(ExitCode.PARSE_ERROR);
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "Error running auto-fix: `buck fix` requires a contact for any configured scripts"));
  }

  @Test
  public void shouldRunScriptAutomaticallyOnCommandFailureIfEnabledForCommandAndTty(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {

    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand(),
                "fix_script_contact",
                "buck@example.com",
                "fix_script_message",
                "Running {command}; contact {contact}",
                "autofix_enabled",
                "INTERACTIVE",
                "autofix_commands",
                "build"),
            "color",
            ImmutableMap.of("ui", "always")));

    ProcessResult result = buckCommand(workspace, test, "build", "//:foo");

    result.assertExitCode(ExitCode.PARSE_ERROR);
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "Running `buck fix`. Invoke this manually with `buck fix --build-id "));
    assertThat(result.getStderr(), Matchers.containsString("command: build"));
    assertThat(result.getStderr(), Matchers.containsString("exit_code: 5"));
    assertThat(result.getStderr(), Matchers.matchesPattern(JASABI_REGEX));
    assertThat(result.getStderr(), Matchers.containsString("manually_invoked: False"));
  }

  @Test
  public void shouldRunScriptAutomaticallyOnCommandFailureIfEnabledForCommand(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {

    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand(),
                "fix_script_contact",
                "buck@example.com",
                "fix_script_message",
                "Running {command}; contact {contact}",
                "autofix_enabled",
                "ALWAYS",
                "autofix_commands",
                "build")));
    ProcessResult result = buckCommand(workspace, test, "build", "//:foo");

    result.assertExitCode(ExitCode.PARSE_ERROR);
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "Running `buck fix`. Invoke this manually with `buck fix --build-id "));
    assertThat(result.getStderr(), Matchers.containsString("command: build"));
    assertThat(result.getStderr(), Matchers.containsString("exit_code: 5"));
    assertThat(result.getStderr(), Matchers.matchesPattern(JASABI_REGEX));
    assertThat(result.getStderr(), Matchers.containsString("manually_invoked: False"));
  }

  @Test
  public void shouldAutomaticallyRunInteractivelyAfterCommandFails(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "fix",
            ImmutableMap.of(
                "fix_script",
                fixPyCommand() + " --wait",
                "fix_script_contact",
                "buck@example.com",
                "fix_script_message",
                "Running {command}; contact {contact}",
                "autofix_enabled",
                "ALWAYS",
                "autofix_commands",
                "build,test")));
    ProcessResult result =
        workspace.runBuckCommand(
            false,
            ImmutableMap.copyOf(test.getVariableMap()),
            Optional.of("foobar"),
            test.getTemplateSet(),
            "build",
            "//:foo");

    result.assertExitCode(ExitCode.PARSE_ERROR);
    assertThat(result.getStderr(), Matchers.containsString("command: build"));
    assertThat(result.getStderr(), Matchers.containsString("exit_code: 5"));
    assertThat(result.getStderr(), Matchers.matchesPattern(JASABI_REGEX));
    assertThat(result.getStderr(), Matchers.containsString("manually_invoked: False"));
    assertThat(result.getStderr(), Matchers.containsString("user entered foobar"));
  }
}
