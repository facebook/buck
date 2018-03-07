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
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

  private static final String TESTDATA_DIRECTORY = "testdata";

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
    if (buckdEnabled) {
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
   * Runs Buck with the specified list of command-line arguments with the given map of environment
   * variables as overrides of the current system environment.
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
    for (String template : templates) {
      this.addPremadeTemplate(template);
    }
    ImmutableList.Builder<String> commandBuilder = platformUtils.getCommandBuilder();
    List<String> command = commandBuilder.addAll(ImmutableList.copyOf(args)).build();
    ImmutableMap<String, String> environment =
        overrideSystemEnvironment(buckdEnabled, environmentOverrides);
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(command)
            .setEnvironment(environment)
            .setDirectory(destPath.toAbsolutePath())
            .build();
    ProcessExecutor.Result result = processExecutor.launchAndExecute(params);
    ranWithBuckd = ranWithBuckd || buckdEnabled;
    return new ProcessResult(
        ExitCode.map(result.getExitCode()),
        result.getStdout().orElse(""),
        result.getStderr().orElse(""));
  }

  /** Replaces platform-specific placeholders configurations with their appropriate replacements */
  private void postAddPlatformConfiguration() throws IOException {
    platformUtils.checkAssumptions();
    platformUtils.setUpWorkspace(this);
  }

  /**
   * Copies the template directory of a premade template, contained in endtoend/testdata, stripping
   * the suffix from files with suffix .fixture, and not copying files with suffix .expected
   */
  public void addPremadeTemplate(String templateName) throws Exception {
    URL testDataResource = getClass().getResource(TESTDATA_DIRECTORY);
    // If we're running this test in IJ, then this path doesn't exist. Fall back to one that does
    if (testDataResource != null) {
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
