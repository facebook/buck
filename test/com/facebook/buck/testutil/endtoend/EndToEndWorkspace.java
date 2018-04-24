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

package com.facebook.buck.testutil.endtoend;

import com.facebook.buck.testutil.AbstractWorkspace;
import com.facebook.buck.testutil.PlatformUtils;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.LaunchedProcess;
import com.facebook.buck.util.ProcessExecutor.LaunchedProcessImpl;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.ProcessHelper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * {@link EndToEndWorkspace} provides an interface to create temporary workspaces for the use in
 * EndToEndTests. It implements junit {@link TestRule}, and so can be used by:
 *
 * <ul>
 *   <li>In test class, preceding the declaration of the Workspace with @Rule will automatically
 *       create and destroy the workspace before each test.
 *   <li>Calling {@code .setup} and {@code .teardown} before and after testing manually
 * </ul>
 */
public class EndToEndWorkspace extends AbstractWorkspace implements TestRule {
  private TemporaryPaths tempPath = new TemporaryPaths();
  private final ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());
  private Boolean ranWithBuckd = false;
  private final ProcessHelper processHelper = ProcessHelper.getInstance();

  private static final String TESTDATA_DIRECTORY = "testdata";
  private static final Long buildResultTimeoutMS = TimeUnit.SECONDS.toMillis(5);
  private Set<String> addedTemplates = new HashSet<>();

  private PlatformUtils platformUtils = PlatformUtils.getForPlatform();

  /**
   * Constructor for EndToEndWorkspace. Note that setup and teardown should be called in between
   * tests (easy way to do this is to use @Rule)
   */
  public EndToEndWorkspace() {}

  /**
   * Used for @Rule functionality so that EndToEndWorkspace can be automatically set up and torn
   * down before every test.
   *
   * @param base statement that contains the test
   * @param description a description of the test
   * @return transformed statement that sets up and tears down before and after the test
   */
  @Override
  public Statement apply(Statement base, Description description) {
    return statement(base);
  }

  /** Sets up TemporaryPaths for the test, and sets the Workspace's root to that path */
  public void setup() throws Throwable {
    this.tempPath.before();
    this.destPath = tempPath.getRoot();
    System.out.println("EndToEndWorkspace created");
  }

  /**
   * Tears down TemporaryPaths, and resets Workspace's roots to avoid polluting execution
   * environment accidentally.
   */
  public void teardown() {
    if (this.ranWithBuckd) {
      try {
        this.runBuckCommand("kill");
      } catch (Exception e) {
        // Nothing particularly better to do than log the error and swallow it
        System.err.println(e.getMessage());
      }
    }
    this.tempPath.after();
    this.destPath = null;
    System.out.println("EndToEndWorkspace torn down");
  }

  private Statement statement(Statement base) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        setup();
        try {
          base.evaluate();
        } finally {
          teardown();
        }
      }
    };
  }

  private ImmutableMap<String, String> overrideSystemEnvironment(
      Boolean buckdEnabled, ImmutableMap<String, String> environmentOverrides) {
    ImmutableMap.Builder<String, String> environmentBuilder = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
      if (entry.getKey() == "NO_BUCKD" && buckdEnabled) continue;
      environmentBuilder.put(entry.getKey(), entry.getValue());
    }
    if (!buckdEnabled) {
      environmentBuilder.put("NO_BUCKD", "1");
    }
    environmentBuilder.putAll(environmentOverrides);
    return environmentBuilder.build();
  }

  /**
   * Runs Buck with the specified list of command-line arguments, and the current system environment
   * variables.
   *
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *     {@code ["project"]}, etc.
   * @return the result of running Buck, which includes the exit code, stdout, and stderr.
   */
  @Override
  public ProcessResult runBuckCommand(String... args) throws Exception {
    ImmutableMap<String, String> environment = ImmutableMap.of();
    return runBuckCommand(environment, args);
  }

  /**
   * Launches Buck process (non-blocking) with the specified list of command-line arguments, and the
   * current system environment variables.
   *
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *     {@code ["project"]}, etc.
   * @return the launched buck process
   */
  public LaunchedProcess launchBuckCommandProcess(String... args) throws Exception {
    ImmutableMap<String, String> environment = ImmutableMap.of();
    return launchBuckCommandProcess(environment, args);
  }

  /**
   * Runs Buck with the specified list of command-line arguments, and the current system environment
   * variables.
   *
   * @param buckdEnabled determines whether the command is run with buckdEnabled or not
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *     {@code ["project"]}, etc.
   * @return the result of running Buck, which includes the exit code, stdout, and stderr.
   */
  public ProcessResult runBuckCommand(Boolean buckdEnabled, String... args) throws Exception {
    ImmutableMap<String, String> environment = ImmutableMap.of();
    String[] templates = new String[] {};
    return runBuckCommand(buckdEnabled, environment, templates, args);
  }

  /**
   * Launches Buck process (non-blocking) with the specified list of command-line arguments, and the
   * current system environment variables.
   *
   * @param buckdEnabled determines whether the command is run with buckdEnabled or not
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *     {@code ["project"]}, etc.
   * @return the launched buck process
   */
  public LaunchedProcess launchBuckCommandProcess(Boolean buckdEnabled, String... args)
      throws Exception {
    ImmutableMap<String, String> environment = ImmutableMap.of();
    String[] templates = new String[] {};
    return launchBuckCommandProcess(buckdEnabled, environment, templates, args);
  }

  /**
   * Runs Buck with the specified list of command-line arguments with the given map of environment
   * variables as overrides of the current system environment.
   *
   * @param environmentOverrides set of environment variables to override
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *     {@code ["project"]}, etc.
   * @return the result of running Buck, which includes the exit code, stdout, and stderr.
   */
  @Override
  public ProcessResult runBuckCommand(
      ImmutableMap<String, String> environmentOverrides, String... args) throws Exception {
    String[] templates = new String[] {};
    return runBuckCommand(false, environmentOverrides, templates, args);
  }

  /**
   * Launches Buck process (non-blocking) with the specified list of command-line arguments with the
   * given map of environment variables as overrides of the current system environment.
   *
   * @param environmentOverrides set of environment variables to override
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *     {@code ["project"]}, etc.
   * @return the launched buck process
   */
  public LaunchedProcess launchBuckCommandProcess(
      ImmutableMap<String, String> environmentOverrides, String... args) throws Exception {
    String[] templates = new String[] {};
    return launchBuckCommandProcess(false, environmentOverrides, templates, args);
  }

  /**
   * Runs buck given command-line arguments and environment variables as overrides of the current
   * system environment based on a given EndToEndTestDescriptor
   *
   * @param testDescriptor provides buck command arguments/environment variables
   * @return the result of running Buck, which includes the exit code, stdout, and stderr.
   */
  public ProcessResult runBuckCommand(EndToEndTestDescriptor testDescriptor) throws Exception {
    return runBuckCommand(
        testDescriptor.getBuckdEnabled(),
        ImmutableMap.copyOf(testDescriptor.getVariableMap()),
        testDescriptor.getTemplateSet(),
        testDescriptor.getFullCommand());
  }

  /**
   * Launches buck process (non-blocking) given command-line arguments and environment variables as
   * overrides of the current system environment based on a given EndToEndTestDescriptor
   *
   * @param testDescriptor provides buck command arguments/environment variables
   * @return the launched buck process
   */
  public LaunchedProcess launchBuckCommandProcess(EndToEndTestDescriptor testDescriptor)
      throws Exception {
    return launchBuckCommandProcess(
        testDescriptor.getBuckdEnabled(),
        ImmutableMap.copyOf(testDescriptor.getVariableMap()),
        testDescriptor.getTemplateSet(),
        testDescriptor.getFullCommand());
  }

  /**
   * Runs Buck with the specified list of command-line arguments with the given map of environment
   * variables as overrides of the current system environment. This command is blocking.
   *
   * @param buckdEnabled determines whether the command is run with buckdEnabled or not
   * @param environmentOverrides set of environment variables to override
   * @param templates is an array of premade templates to add to the workspace before running the
   *     command.
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *     {@code ["project"]}, etc.
   * @return the result of running Buck, which includes the exit code, stdout, and stderr.
   */
  public ProcessResult runBuckCommand(
      Boolean buckdEnabled,
      ImmutableMap<String, String> environmentOverrides,
      String[] templates,
      String... args)
      throws Exception {
    System.out.println("Running buck command: " + String.join(" ", args));
    for (String template : templates) {
      this.addPremadeTemplate(template);
    }
    ImmutableList.Builder<String> commandBuilder = platformUtils.getBuckCommandBuilder();
    List<String> command = commandBuilder.addAll(ImmutableList.copyOf(args)).build();
    ranWithBuckd = ranWithBuckd || buckdEnabled;
    return runCommand(buckdEnabled, environmentOverrides, command, Optional.empty());
  }

  /**
   * Launches a Buck process (non-blocking) with the specified list of command-line arguments with
   * the given map of environment variables as overrides of the current system environment.
   *
   * @param buckdEnabled determines whether the command is run with buckdEnabled or not
   * @param environmentOverrides set of environment variables to override
   * @param templates is an array of premade templates to add to the workspace before running the
   *     command.
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *     {@code ["project"]}, etc.
   * @return the launched buck process
   */
  public LaunchedProcess launchBuckCommandProcess(
      Boolean buckdEnabled,
      ImmutableMap<String, String> environmentOverrides,
      String[] templates,
      String... args)
      throws Exception {
    System.out.println("Launching buck command: " + String.join(" ", args));
    for (String template : templates) {
      this.addPremadeTemplate(template);
    }
    ImmutableList.Builder<String> commandBuilder = platformUtils.getBuckCommandBuilder();
    List<String> command = commandBuilder.addAll(ImmutableList.copyOf(args)).build();
    ranWithBuckd = ranWithBuckd || buckdEnabled;
    return launchCommandProcess(buckdEnabled, environmentOverrides, command);
  }

  private int getLaunchedPid(LaunchedProcess launchedProcess) {
    Preconditions.checkState(launchedProcess instanceof LaunchedProcessImpl);
    Process p = ((LaunchedProcessImpl) launchedProcess).process;
    return Math.toIntExact(processHelper.getPid(p));
  }

  /** Sends interrupt signal to the given launched process */
  public void sendSigInt(LaunchedProcess launchedProcess) throws IOException {
    Runtime.getRuntime().exec(String.format("kill -SIGINT %d", getLaunchedPid(launchedProcess)));
  }

  /**
   * Waits for given launched process to terminate, with 5 second timeout
   *
   * @return whether the process has terminated or not
   */
  public boolean waitForProcess(LaunchedProcess launchedProcess) throws InterruptedException {
    return !processExecutor
        .waitForLaunchedProcessWithTimeout(launchedProcess, 5000, Optional.empty())
        .isTimedOut();
  }

  /**
   * Runs a given built command and buckdEnabled/environmentOverride settings, and returns a {@link
   * ProcessResult} If an empty timeoutMS is given, then the command will have no timeout.
   */
  private ProcessResult runCommand(
      boolean buckdEnabled,
      ImmutableMap<String, String> environmentOverrides,
      List<String> command,
      Optional<Long> timeoutMS)
      throws Exception {
    ImmutableMap<String, String> environment =
        overrideSystemEnvironment(buckdEnabled, environmentOverrides);
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(command)
            .setEnvironment(environment)
            .setDirectory(destPath.toAbsolutePath())
            .build();
    ProcessExecutor.Result result =
        processExecutor.launchAndExecute(
            params,
            /* context */ ImmutableMap.of(),
            /* options */ ImmutableSet.of(),
            /* stdin */ Optional.empty(),
            timeoutMS,
            /* timeoutHandler */ Optional.empty());
    return new ProcessResult(
        ExitCode.map(result.getExitCode()),
        result.getStdout().orElse(""),
        result.getStderr().orElse(""),
        result.isTimedOut());
  }

  /**
   * Launches a given built command with buckdEnabled/environmentOverrides settings, and returns a
   * {@link com.facebook.buck.util.ProcessExecutor.LaunchedProcess}. This command does not block.
   */
  public LaunchedProcess launchCommandProcess(
      boolean buckdEnabled, ImmutableMap<String, String> environmentOverrides, List<String> command)
      throws Exception {
    ImmutableMap<String, String> environment =
        overrideSystemEnvironment(buckdEnabled, environmentOverrides);
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(command)
            .setEnvironment(environment)
            .setDirectory(destPath.toAbsolutePath())
            .build();
    return processExecutor.launchProcess(params);
  }

  /** Replaces platform-specific placeholders configurations with their appropriate replacements */
  private void postAddPlatformConfiguration() throws IOException {
    platformUtils.checkAssumptions();
  }

  /**
   * Runs the process that is built by the given fully qualified target name. The program should be
   * already built to run it.
   */
  public ProcessResult runBuiltResult(String target, String... args) throws Exception {
    String fullTarget = EndToEndHelper.getProperBuildTarget(target);
    ProcessResult targetsResult =
        runBuckCommand(ranWithBuckd, "targets", fullTarget, "--show-output");
    if (targetsResult.getExitCode().getCode() > 0) {
      throw new RuntimeException(
          "buck targets command getting outputPath failed: " + targetsResult.getStderr());
    }
    String[] targetsOutput = targetsResult.getStdout().split(" ");
    if (targetsOutput.length != 2) {
      throw new IllegalStateException(
          "Expect to receive single target and path pair from buck targets --show-output, got:"
              + targetsResult.getStdout());
    }
    String outputPath = targetsOutput[1].replaceAll("[ \n]", "");
    ImmutableList.Builder<String> commandBuilder = platformUtils.getCommandBuilder();
    List<String> command =
        commandBuilder.add(outputPath).addAll(ImmutableList.copyOf(args)).build();
    return runCommand(
        ranWithBuckd,
        ImmutableMap.<String, String>builder().build(),
        command,
        Optional.of(buildResultTimeoutMS));
  }

  /**
   * Copies the template directory of a premade template, contained in endtoend/testdata, stripping
   * the suffix from files with suffix .fixture, and not copying files with suffix .expected
   */
  public void addPremadeTemplate(String templateName) throws Exception {
    if (addedTemplates.contains(templateName)) {
      return; // already added template
    }
    addedTemplates.add(templateName);
    URL testDataResource = getClass().getResource(TESTDATA_DIRECTORY);
    // If we're running this test in IJ, then this path doesn't exist. Fall back to one that does
    if (testDataResource != null && testDataResource.getPath().contains(".jar")) {
      this.addTemplateToWorkspace(testDataResource, templateName);
    } else {
      Path templatePath =
          FileSystems.getDefault()
              .getPath(
                  "test",
                  "com",
                  "facebook",
                  "buck",
                  "testutil",
                  "endtoend",
                  TESTDATA_DIRECTORY,
                  templateName);
      addTemplateToWorkspace(templatePath);
    }
    postAddPlatformConfiguration();
  }
}
