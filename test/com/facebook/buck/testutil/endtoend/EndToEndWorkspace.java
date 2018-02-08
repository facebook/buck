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
  private TemporaryPaths tempPath;
  private Boolean buckdEnabled;
  private final ProcessExecutor processExecutor;

  private static final String TESTDATA_DIRECTORY = "testdata";

  // TODO: Move to work on windows, and to use Resources
  private static final String BUCK_EXE =
      FileSystems.getDefault()
          .getPath("buck-out", "gen", "programs", "buck.pex")
          .toAbsolutePath()
          .toString();

  /**
   * Constructor for EndToEndWorkspace. Note that setup and teardown should be called in between
   * tests (easy way to do this is to use @Rule)
   */
  public EndToEndWorkspace() {
    super();
    this.tempPath = new TemporaryPaths();
    this.processExecutor = new DefaultProcessExecutor(new TestConsole());
    this.buckdEnabled = false;
  }

  /**
   * Used for @Rule functionality so that EndToEndWorkspace can be automatically set up and torn
   * down before every test.
   *
   * @param base statement that contains the test
   * @param description a description of the test
   * @return transformed statement that sets up and tears down before and after the test
   */
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
    if (this.buckdEnabled) {
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

  private Statement statement(final Statement base) {
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
      ImmutableMap<String, String> environmentOverrides) {
    ImmutableMap.Builder<String, String> environmentBuilder =
        ImmutableMap.<String, String>builder();
    for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
      if (entry.getKey() == "NO_BUCKD" && this.buckdEnabled) continue;
      environmentBuilder.put(entry.getKey(), entry.getValue());
    }
    if (!this.buckdEnabled) {
      environmentBuilder.put("NO_BUCKD", "1");
    }
    environmentBuilder.putAll(environmentOverrides);
    return environmentBuilder.build();
  }

  /** Enables the usage of Buckd while running buck commands. */
  public EndToEndWorkspace withBuckd() {
    this.buckdEnabled = true;
    return this;
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
  public ProcessResult runBuckCommand(String... args) throws InterruptedException, IOException {
    ImmutableMap<String, String> environment = ImmutableMap.of();
    return runBuckCommand(environment, args);
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
      ImmutableMap<String, String> environmentOverrides, String... args)
      throws InterruptedException, IOException {
    List<String> command =
        ImmutableList.<String>builder().add(BUCK_EXE).addAll(ImmutableList.copyOf(args)).build();
    ImmutableMap<String, String> environment = overrideSystemEnvironment(environmentOverrides);
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(command)
            .setEnvironment(environment)
            .setDirectory(destPath.toAbsolutePath())
            .build();
    ProcessExecutor.Result result = processExecutor.launchAndExecute(params);
    return new ProcessResult(
        ExitCode.map(result.getExitCode()),
        result.getStdout().orElse(""),
        result.getStderr().orElse(""));
  }

  /**
   * Copies the template directory of a premade template, contained in endtoend/testdata, stripping
   * the suffix from files with suffix .fixture, and not copying files with suffix .expected
   */
  public void addPremadeTemplate(String templateName) throws IOException {
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
    this.addTemplateToWorkspace(templatePath);
  }
}
