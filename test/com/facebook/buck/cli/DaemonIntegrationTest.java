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

package com.facebook.buck.cli;

import static java.util.concurrent.Executors.callable;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.watchman.WatchmanWatcher;
import com.facebook.buck.logd.client.FileOutputStreamFactory;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.bgtasks.TestBackgroundTaskManager;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.EnvironmentSanitizer;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.nailgun.NGClientListener;
import com.facebook.nailgun.NGContext;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class DaemonIntegrationTest {

  private ScheduledExecutorService executorService;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public TestWithBuckd testWithBuckd = new TestWithBuckd(tmp);

  @Before
  public void setUp() {
    executorService = Executors.newScheduledThreadPool(5);
  }

  @After
  public void tearDown() {
    Thread.interrupted(); // Clear interrupted flag, if set.
    executorService.shutdown();
  }

  /**
   * This verifies that when the user tries to run a read/write command, while another is already
   * running, the second call will fail. Serializing command execution in this way avoids multiple
   * threads accessing and corrupting the static state used by the Buck daemon and trampling over
   * each others output.
   */
  @Test
  public void whenConcurrentCommandExecutedThenSecondCommandFails()
      throws IOException, InterruptedException, ExecutionException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exclusive_execution", tmp);
    workspace.setUp();
    Future<?> firstThread =
        executorService.schedule(
            createRunnableCommand(ExitCode.SUCCESS, "build", "//:sleep"), 0, TimeUnit.MILLISECONDS);
    Future<?> secondThread =
        executorService.schedule(
            createRunnableCommand(ExitCode.BUSY, "build", "//:sleep"), 500L, TimeUnit.MILLISECONDS);
    firstThread.get();
    secondThread.get();
  }

  @Test
  public void whenConcurrentReadOnlyCommandExecutedThenReadOnlyCommandSucceeds()
      throws IOException, InterruptedException, ExecutionException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exclusive_execution", tmp);
    workspace.setUp();
    Future<?> firstThread =
        executorService.schedule(
            createRunnableCommand(ExitCode.SUCCESS, "build", "//:sleep"), 0, TimeUnit.MILLISECONDS);
    Future<?> secondThread =
        executorService.schedule(
            createRunnableCommand(ExitCode.SUCCESS, "targets", "//..."),
            500L,
            TimeUnit.MILLISECONDS);
    firstThread.get();
    secondThread.get();
  }

  /** This verifies that multiple read only commands can be executed concurrently successfully. */
  @Test
  public void whenReadOnlyCommandsExecutedConcurrentlyThenAllSucceed()
      throws IOException, InterruptedException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exclusive_execution", tmp);
    workspace.setUp();
    executorService.invokeAll(
        ImmutableList.of(
            createCallableCommand(ExitCode.SUCCESS, "audit", "input", "//:sleep"),
            createCallableCommand(ExitCode.SUCCESS, "audit", "input", "//:sleep"),
            createCallableCommand(ExitCode.SUCCESS, "audit", "input", "//:sleep"),
            createCallableCommand(ExitCode.SUCCESS, "audit", "input", "//:sleep"),
            createCallableCommand(ExitCode.SUCCESS, "audit", "input", "//:sleep")));
  }

  private Runnable createRunnableCommand(ExitCode expectedExitCode, String... args) {
    return () -> {
      try {
        // TODO we should get rid of this and just use the ProjectWorkspace or use EndToEndTest
        BackgroundTaskManager manager = TestBackgroundTaskManager.of();
        TestContext context = new TestContext();
        MainForTests main =
            new MainForTests(
                new TestConsole(),
                new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)),
                tmp.getRoot().getPath(),
                tmp.getRoot().getPath().toAbsolutePath().toString(),
                EnvironmentSanitizer.getSanitizedEnvForTests(ImmutableMap.of()),
                DaemonMode.DAEMON);

        MainRunner mainRunner =
            main.prepareMainRunner(
                manager,
                testWithBuckd.getGlobalState(),
                new MainWithNailgun.DaemonCommandManager(context));
        ExitCode exitCode =
            mainRunner.runMainWithExitCode(
                new FileOutputStreamFactory(),
                WatchmanWatcher.FreshInstanceAction.NONE,
                System.nanoTime(),
                ImmutableList.copyOf(args),
                t -> {
                  throw t;
                });
        assertEquals("Unexpected exit code.", expectedExitCode, exitCode);
      } catch (InterruptedException e) {
        fail("Should not throw exception.");
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        fail("Should not throw exception.");
        Throwables.throwIfUnchecked(e);
      }
    };
  }

  private Callable<Object> createCallableCommand(ExitCode expectedExitCode, String... args) {
    return callable(createRunnableCommand(expectedExitCode, args));
  }

  @Test
  public void whenAppBuckFileRemovedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("build", "app");
    result.assertSuccess();

    String fileName = "apps/myapp/BUCK";
    Files.delete(workspace.getPath(fileName));

    workspace.runBuckCommand("build", "app").assertExitCode(null, ExitCode.PARSE_ERROR);
  }

  @Test
  public void whenActivityBuckFileRemovedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/BUCK";
    Files.delete(workspace.getPath(fileName));

    workspace
        .runBuckCommand("build", "//java/com/example/activity:activity")
        .assertExitCode(null, ExitCode.PARSE_ERROR);
  }

  @Parameters({"FILESYSTEM_CRAWL", "WATCHMAN"})
  @Test
  public void whenAppBuckFileRemovedThenFailingRecursiveRebuildSucceeds(
      ParserConfig.BuildFileSearchMethod buildFileSearchMethod) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    workspace.setBuildFileSearchMethodConfig(buildFileSearchMethod);

    String appBuckFile = "apps/myapp/BUCK";
    Files.write(
        workspace.getPath(appBuckFile), "Some Illegal Python".getBytes(StandardCharsets.UTF_8));
    workspace.runBuckCommand("build", "//apps/...").assertExitCode(null, ExitCode.PARSE_ERROR);

    Files.delete(workspace.getPath(appBuckFile));
    workspace.runBuckCommand("build", "//apps/...").assertSuccess();
  }

  @Test
  public void whenSourceInputRemovedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/MyFirstActivity.java";
    Files.delete(workspace.getPath(fileName));

    ProcessResult processResult =
        workspace.runBuckCommand("build", "//java/com/example/activity:activity");
    processResult.assertFailure();
    assertThat(
        "Failure should have been due to file removal.",
        processResult.getStderr(),
        containsString("MyFirstActivity.java"));
  }

  @Test
  public void whenSourceInputInvalidatedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/MyFirstActivity.java";
    Files.delete(workspace.getPath(fileName));

    ProcessResult processResult =
        workspace.runBuckCommand("build", "//java/com/example/activity:activity");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("MyFirstActivity.java"));
  }

  @Test
  public void whenAppBuckFileInvalidatedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "app").assertSuccess();

    String fileName = "apps/myapp/BUCK";
    Files.write(
        workspace.getPath(fileName), "Some Illegal Python".getBytes(StandardCharsets.UTF_8));

    ProcessResult result = workspace.runBuckCommand("build", "app");
    assertThat(
        "Failure should be due to syntax error.",
        result.getStderr(),
        containsString("Cannot parse"));
    result.assertExitCode(null, ExitCode.PARSE_ERROR);
  }

  @Test
  public void whenNativeBuckTargetInvalidatedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("run", "//native/main:main");
    result.assertSuccess();
    assertThat(
        "Output should contain 'my_string_123_my_string'",
        result.getStdout(),
        containsString("my_string_123_my_string"));

    workspace.replaceFileContents("native/lib/BUCK", "123", "456");

    result = workspace.runBuckCommand("run", "//native/main:main");
    result.assertSuccess();
    assertThat(
        "Output should contain 'my_string_456_my_string'",
        result.getStdout(),
        containsString("my_string_456_my_string"));
  }

  @Test
  public void whenNativeSourceInputInvalidatedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("run", "//native/main:main");
    result.assertSuccess();
    assertThat(
        "Output should contain 'my_string_123_my_string'",
        result.getStdout(),
        containsString("my_string_123_my_string"));

    workspace.replaceFileContents(
        "native/lib/lib.cpp", "THE_STRING", "\"my_string_456_my_string\"");

    result = workspace.runBuckCommand("run", "//native/main:main");
    result.assertSuccess();
    assertThat(
        "Output should contain 'my_string_456_my_string'",
        result.getStdout(),
        containsString("my_string_456_my_string"));
  }

  @Test
  public void whenCrossCellSourceInvalidatedThenRebuildFails() throws IOException {
    ProjectWorkspace primary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/primary", tmp.newFolder());
    primary.setUp();
    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/secondary", tmp.newFolder());
    secondary.setUp();
    TestDataHelper.overrideBuckconfig(
        primary,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));
    primary.runBuckCommand("build", "//:cxxbinary").assertSuccess();
    ProcessResult result = primary.runBuckCommand("run", "//:cxxbinary");
    result.assertSuccess();

    String fileName = "sum.cpp";
    Files.write(secondary.getPath(fileName), "#error Failure".getBytes(StandardCharsets.UTF_8));

    result = primary.runBuckCommand("build", "//:cxxbinary");
    assertThat(
        "Failure should be due to compilation error.",
        result.getStderr(),
        containsString("#error Failure"));
    result.assertFailure();

    // Make the file valid again, but change the output
    Files.write(
        secondary.getPath(fileName),
        "#include \"sum.hpp\"\nint sum(int a, int b) {return a;}".getBytes(StandardCharsets.UTF_8));

    primary.runBuckCommand("build", "//:cxxbinary").assertSuccess();
    result = primary.runBuckCommand("run", "//:cxxbinary");
    result.assertFailure();
  }

  @Test
  public void whenCrossCellBuckFileInvalidatedThenRebuildFails() throws IOException {
    ProjectWorkspace primary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/primary", tmp.newFolder());
    primary.setUp();
    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/secondary", tmp.newFolder());
    secondary.setUp();
    TestDataHelper.overrideBuckconfig(
        primary,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));
    primary.runBuckCommand("build", "//:cxxbinary").assertSuccess();

    String fileName = "BUCK";
    Files.write(
        secondary.getPath(fileName), "Some Invalid Python".getBytes(StandardCharsets.UTF_8));

    ProcessResult processResult = primary.runBuckCommand("build", "//:cxxbinary");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "This error happened while trying to get dependency 'secondary//:cxxlib' of target '//:cxxbinary'"));
  }

  @Test
  public void whenBuckBuiltTwiceLogIsPresent() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//java/com/example/activity:activity").assertSuccess();

    Path buildLogFile = workspace.getPath("buck-out/bin/build.log");

    assertTrue(Files.isRegularFile(buildLogFile));
    assertThat(Files.readAllLines(buildLogFile), hasItem(containsString("BUILT_LOCALLY")));
    Files.delete(buildLogFile);

    ProcessResult rebuild =
        workspace.runBuckCommand("build", "//java/com/example/activity:activity");
    rebuild.assertSuccess();

    buildLogFile = workspace.getPath("buck-out/bin/build.log");
    assertTrue(Files.isRegularFile(buildLogFile));
    assertThat(Files.readAllLines(buildLogFile), not(hasItem(containsString("BUILT_LOCALLY"))));
  }

  @Test
  public void whenNativeTargetBuiltTwiceCacheHits() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/primary", tmp);
    workspace.setUp();

    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/secondary", tmp.newFolder());
    secondary.setUp();
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));

    workspace.runBuckCommand("build", "//:cxxbinary").assertSuccess();

    Path buildLogFile = workspace.getPath("buck-out/bin/build.log");
    assertTrue(Files.isRegularFile(buildLogFile));
    assertThat(Files.readAllLines(buildLogFile), hasItem(containsString("BUILT_LOCALLY")));
    Files.delete(buildLogFile);

    workspace.runBuckCommand("build", "//:cxxbinary").assertSuccess();
    buildLogFile = workspace.getPath("buck-out/bin/build.log");
    assertTrue(Files.isRegularFile(buildLogFile));
    assertThat(Files.readAllLines(buildLogFile), not(hasItem(containsString("BUILT_LOCALLY"))));
  }

  @Test
  public void crossCellLoadChangesInvalidateBuckTargets() throws Exception {
    ProjectWorkspace primary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_load/primary", tmp.newFolder("primary"));
    primary.setUp();

    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_load/secondary", tmp.newFolder("secondary"));
    secondary.setUp();
    TestDataHelper.overrideBuckconfig(
        primary,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));

    primary.runBuckCommand("build", ":rule").assertSuccess();
    Files.write(secondary.getPath("included_by_primary.bzl"), new byte[] {});
    primary.runBuckCommand("build", ":rule").assertExitCode(null, ExitCode.PARSE_ERROR);
  }

  /** NGContext test double. */
  private static class TestContext extends NGContext implements Closeable {

    private final Properties properties;
    private final Set<NGClientListener> listeners;

    /** Simulates client that never disconnects, with normal system environment. */
    public TestContext() {
      this(ImmutableMap.of());
    }

    /** Simulates client that never disconnects, with given environment. */
    public TestContext(ImmutableMap<String, String> environment) {

      ImmutableMap<String, String> sanitizedEnv =
          EnvironmentSanitizer.getSanitizedEnvForTests(environment);

      properties = new Properties();
      for (Map.Entry<String, String> entry : sanitizedEnv.entrySet()) {
        properties.setProperty(entry.getKey(), entry.getValue());
      }
      listeners = new HashSet<>();
      in =
          new InputStream() {
            @Override
            public int read() {
              return -1;
            }
          };
    }

    @Override
    public Properties getEnv() {
      return properties;
    }

    @Override
    public void addClientListener(NGClientListener listener) {
      listeners.add(listener);
    }

    @Override
    public void removeClientListener(NGClientListener listener) {
      listeners.remove(listener);
    }

    @Override
    public void removeAllClientListeners() {
      listeners.clear();
    }

    @Override
    public void exit(int exitCode) {}

    @Override
    public void close() throws IOException {
      in.close();
      out.close();
      err.close();
    }
  }
}
