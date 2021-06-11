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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.main;

import static com.facebook.buck.testutil.TestLogSink.logRecordWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.downwardapi.processexecutor.BaseNamedPipeEventHandler;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.downwardapi.testutil.TestWindowsHandleFactory;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.namedpipes.windows.WindowsNamedPipeFactory;
import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandleFactory;
import com.facebook.buck.javacd.model.AbiGenerationMode;
import com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode;
import com.facebook.buck.javacd.model.BaseJarCommand;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.javacd.model.BuildTargetValue;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.javacd.model.JarParameters;
import com.facebook.buck.javacd.model.JavaAbiInfo;
import com.facebook.buck.javacd.model.LibraryJarBaseCommand;
import com.facebook.buck.javacd.model.LibraryJarCommand;
import com.facebook.buck.javacd.model.OutputPathsValue;
import com.facebook.buck.javacd.model.RelPath;
import com.facebook.buck.javacd.model.ResolvedJavac;
import com.facebook.buck.javacd.model.ResolvedJavacOptions;
import com.facebook.buck.jvm.java.JavaCDWorkerStepUtils;
import com.facebook.buck.testutil.ExecutorServiceUtils;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestLogSink;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.env.BuckClasspath;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.java.JavaRuntimeUtils;
import com.facebook.buck.util.timing.FakeClock;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.facebook.buck.workertool.WorkerToolLauncher;
import com.facebook.buck.workertool.impl.DefaultWorkerToolExecutor;
import com.facebook.buck.workertool.impl.DefaultWorkerToolLauncher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.SettableFuture;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class JavaCDIntegrationTest {

  private static final String PACKAGE_NAME =
      JavaCDIntegrationTest.class.getPackage().getName().replace('.', '/');
  private static final String TEST_BINARY_NAME = "javacd_bin_for_tests";
  private static final String JAVACD_BINARY_TARGET =
      "//test/" + PACKAGE_NAME + ":" + TEST_BINARY_NAME;

  private static final String USER_DIR = System.getProperty("user.dir");
  private static final Path TEST_RESOURCES_BASE =
      TestDataHelper.getTestDataDirectory(JavaCDIntegrationTest.class).resolve("javacd");

  private static WindowsHandleFactory initialWindowsHandleFactory;
  private static final TestWindowsHandleFactory TEST_WINDOWS_HANDLE_FACTORY =
      new TestWindowsHandleFactory();

  private BuckEventBus eventBusForTests;
  private BuckEventBusForTests.CapturingEventListener eventBusListener;
  private IsolatedExecutionContext executionContext;
  private Path testBinary;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule public Timeout globalTestTimeout = Timeout.seconds(180);

  @Rule
  public TestLogSink javacdLogSink =
      new TestLogSink(JavaCDIntegrationTest.class.getPackage().getName() + ".StepExecutionUtils");

  @Rule
  public TestLogSink workerToolLogSink =
      new TestLogSink(DefaultWorkerToolExecutor.class.getCanonicalName());

  @Rule
  public TestLogSink eventHandlerLogSink =
      new TestLogSink(BaseNamedPipeEventHandler.class.getCanonicalName());

  @BeforeClass
  public static void beforeClass() throws Exception {
    // override WindowsHandleFactory with a test one
    initialWindowsHandleFactory = WindowsNamedPipeFactory.windowsHandleFactory;
    WindowsNamedPipeFactory.windowsHandleFactory = TEST_WINDOWS_HANDLE_FACTORY;
  }

  @AfterClass
  public static void afterClass() throws Exception {
    WindowsNamedPipeFactory.windowsHandleFactory = initialWindowsHandleFactory;
  }

  @After
  public void afterTest() {
    if (Platform.detect() == Platform.WINDOWS) {
      TEST_WINDOWS_HANDLE_FACTORY.verifyAllCreatedHandlesClosed();
    }
  }

  @Before
  public void setUp() throws Exception {
    URL url = getBinaryURL();
    testBinary = temporaryFolder.getRoot().getPath().resolve("javacd.jar");
    Files.write(testBinary, Resources.toByteArray(url));

    eventBusForTests = BuckEventBusForTests.newInstance();
    eventBusListener = new BuckEventBusForTests.CapturingEventListener();
    eventBusForTests.register(eventBusListener);
    Console console =
        new Console(Verbosity.STANDARD_INFORMATION, System.out, System.err, Ansi.forceTty());

    executionContext =
        IsolatedExecutionContext.of(
            eventBusForTests.isolated(),
            console,
            Platform.detect(),
            new DefaultProcessExecutor(console),
            AbsPath.get(temporaryFolder.getRoot().toString()),
            ActionId.of("default_action_id"),
            FakeClock.doNotCare());
  }

  @After
  public void tearDown() throws Exception {
    eventBusForTests.unregister(eventBusListener);
    eventBusForTests.close();
  }

  private URL getBinaryURL() throws InterruptedException, IOException {
    URL url =
        JavaCDIntegrationTest.class.getResource(
            "/" + PACKAGE_NAME + "/" + TEST_BINARY_NAME + ".jar");
    if (url != null) {
      return url;
    }

    // in case you are running tests locally from IDE
    ProcessExecutor processExecutor = new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutor.Result result =
        processExecutor.launchAndExecute(
            ProcessExecutorParams.ofCommand(
                "buck", "build", "--show-full-output", JAVACD_BINARY_TARGET));
    assertThat(
        String.format(
            "Exit code is not 0. StdOut: %s %n StdErr: %s", result.getStdout(), result.getStderr()),
        result.getExitCode(),
        equalTo(0));
    Optional<String> stdout = result.getStdout();
    assertThat(stdout.isPresent(), is(true));
    String outputPath = stdout.get().trim().split(" ")[1];
    assertThat(outputPath, not(emptyOrNullString()));
    return new File(outputPath).toURI().toURL();
  }

  @Test
  public void javacdTest() throws Exception {
    String target = "//src/com/facebook/buck/core/test_target:test_target";

    AtomicLong threadIdReference = new AtomicLong();
    WorkerToolLauncher workerToolLauncher = new DefaultWorkerToolLauncher(executionContext);
    try (WorkerToolExecutor workerToolExecutor =
        workerToolLauncher.launchWorker(getLaunchJavaCDCommand(), getEnvs())) {

      AbsPath workingDir = temporaryFolder.newFolder("working_dir");
      String src1 = "src/com/facebook/buck/core/path/TestForwardRelativePath.java";
      String src2 = "src/com/facebook/buck/core/path/TestGenruleOutPath.java";
      for (String src : new String[] {src1, src2}) {
        Path targetPath = Paths.get(workingDir.toString(), src);
        Files.createDirectories(targetPath);
        Files.copy(
            TEST_RESOURCES_BASE.resolve(src), targetPath, StandardCopyOption.REPLACE_EXISTING);
      }

      String jdkJar = "third-party/java/jdk/jdk8-rt-stub.jar";
      Path jdkTarget = Paths.get(workingDir.toString(), jdkJar);
      Files.createDirectories(jdkTarget);
      Files.copy(Paths.get(USER_DIR, jdkJar), jdkTarget, StandardCopyOption.REPLACE_EXISTING);

      for (String dep :
          new String[] {
            "exceptions-abi.jar",
            "filesystems-abi.jar",
            "platform-abi.jar",
            "guava-abi.jar",
            "jsr305-abi.jar"
          }) {
        Path targetPath = Paths.get(workingDir.toString(), "libs/" + dep);
        Files.createDirectories(targetPath);
        Files.copy(
            TEST_RESOURCES_BASE.resolve("libs").resolve(dep),
            targetPath,
            StandardCopyOption.REPLACE_EXISTING);
      }

      BuildJavaCommand buildJavaCommand = createBuildCommand(target, workingDir.toString());

      AtomicReference<SettableFuture<ResultEvent>> resultEventFutureReference =
          new AtomicReference<>();
      // Run in thread that is different from main thread's. Would create a mapping between thread
      // id and action id.
      ExecutorService executorService = Executors.newSingleThreadExecutor();
      try {
        Future<Void> f =
            executorService.submit(
                () -> {
                  threadIdReference.set(Thread.currentThread().getId());

                  resultEventFutureReference.set(
                      workerToolExecutor.executeCommand(
                          ActionId.of("//my_test_action_id"), buildJavaCommand));

                  return null;
                });
        f.get(1, TimeUnit.SECONDS);
      } finally {
        executorService.shutdownNow();
      }

      SettableFuture<ResultEvent> resultEventSettableFuture = resultEventFutureReference.get();
      ResultEvent event = resultEventSettableFuture.get();
      assertThat(event.getActionId(), equalTo("//my_test_action_id"));
      assertThat(event.getExitCode(), equalTo(0));
    }

    waitTillEventsProcessed();

    List<String> actualStepEvents = eventBusListener.getStepEventLogMessages();
    assertThat(actualStepEvents, hasSize(28));

    List<String> simplePerfEvents = eventBusListener.getSimplePerfEvents();
    assertThat(simplePerfEvents, hasSize(4));

    SimplePerfEvent.Started buildTargetStartedEvent =
        SimplePerfEvent.started(SimplePerfEvent.PerfEventTitle.of(target));
    SimplePerfEvent.Started javacStartedEvent =
        SimplePerfEvent.started(SimplePerfEvent.PerfEventTitle.of("javac-1"));

    assertThat(
        simplePerfEvents,
        hasItems(buildTargetStartedEvent.toLogMessage(), javacStartedEvent.toLogMessage()));

    Set<Long> eventsThreadIds = eventBusListener.getEventsThreadIds();
    assertThat(eventsThreadIds, hasSize(1));
    long threadId = threadIdReference.get();
    assertThat(threadId, not(equalTo(Thread.currentThread().getId())));
    assertThat(Iterables.getOnlyElement(eventsThreadIds), equalTo(threadId));
  }

  @Test
  public void javacdTestWithCompilationError() throws Exception {
    String target = "//src/com/facebook/buck/core/test_target:test_target";

    WorkerToolLauncher workerToolLauncher = new DefaultWorkerToolLauncher(executionContext);
    try (WorkerToolExecutor workerToolExecutor =
        workerToolLauncher.launchWorker(getLaunchJavaCDCommand(), getEnvs())) {

      AbsPath workingDir = temporaryFolder.newFolder("working_dir");
      String src1 = "src/com/facebook/buck/core/path/TestForwardRelativePath.java";
      String src2 = "src/com/facebook/buck/core/path/TestGenruleOutPath.java";
      for (String src : new String[] {src1, src2}) {
        Path targetPath = Paths.get(workingDir.toString(), src);
        Files.createDirectories(targetPath);
        Files.copy(
            TEST_RESOURCES_BASE.resolve(src), targetPath, StandardCopyOption.REPLACE_EXISTING);

        // replace content in file
        String content = new String(Files.readAllBytes(targetPath), UTF_8);
        content = content.replaceAll("hashCode()", "h@shC0de()");
        Files.write(targetPath, content.getBytes(UTF_8));
      }

      String jdkJar = "third-party/java/jdk/jdk8-rt-stub.jar";
      Path jdkTarget = Paths.get(workingDir.toString(), jdkJar);
      Files.createDirectories(jdkTarget);
      Files.copy(Paths.get(USER_DIR, jdkJar), jdkTarget, StandardCopyOption.REPLACE_EXISTING);

      for (String dep :
          new String[] {
            "exceptions-abi.jar",
            "filesystems-abi.jar",
            "platform-abi.jar",
            "guava-abi.jar",
            "jsr305-abi.jar"
          }) {
        Path targetPath = Paths.get(workingDir.toString(), "libs/" + dep);
        Files.createDirectories(targetPath);
        Files.copy(
            TEST_RESOURCES_BASE.resolve("libs").resolve(dep),
            targetPath,
            StandardCopyOption.REPLACE_EXISTING);
      }

      BuildJavaCommand buildJavaCommand = createBuildCommand(target, workingDir.toString());

      ResultEvent resultEvent =
          workerToolExecutor
              .executeCommand(ActionId.of("//my_test_action_id"), buildJavaCommand)
              .get();

      assertThat(resultEvent.getActionId(), equalTo("//my_test_action_id"));
      assertThat(resultEvent.getExitCode(), equalTo(1));
    }

    waitTillEventsProcessed();

    assertThat(
        javacdLogSink.getRecords(),
        hasItem(
            logRecordWithMessage(
                stringContainsInOrder("Failed to execute isolated step", "h@shC0de()"))));
  }

  @Test
  public void sendCommandToKilledProcess() throws Exception {
    WorkerToolLauncher workerToolLauncher = new DefaultWorkerToolLauncher(executionContext);
    try (WorkerToolExecutor workerToolExecutor =
        workerToolLauncher.launchWorker(getLaunchJavaCDCommand(), getEnvs())) {

      // stop the process
      workerToolExecutor.close();

      // send shutdown command
      DefaultWorkerToolExecutor defaultWorkerToolExecutor =
          (DefaultWorkerToolExecutor) workerToolExecutor;
      defaultWorkerToolExecutor.sendShutdownCommand();
    }

    waitTillEventsProcessed();
    if (Platform.detect() == Platform.WINDOWS) {
      assertThat(
          eventHandlerLogSink.getRecords(),
          hasItem(logRecordWithMessage(stringContainsInOrder("Named pipe", "is closed"))));
    } else {
      assertThat(
          workerToolLogSink.getRecords(),
          hasItem(
              logRecordWithMessage(
                  stringContainsInOrder(
                      "Cannot write shutdown command for named pipe", "Worker id: "))));
    }
  }

  private BuildJavaCommand createBuildCommand(String target, String baseDirectory) {
    String outputPathDir = "output";
    String sourceAbiOutputPathDir = "output#source-abi";
    String sourceOnlyAbiOutputPathDir = "output#source-only-abi";

    BuildJavaCommand.Builder buildJavaCommandBuilder = BuildJavaCommand.newBuilder();
    buildJavaCommandBuilder
        .getBaseCommandParamsBuilder()
        .setWithDownwardApi(true)
        .setSpoolMode(SpoolMode.DIRECT_TO_JAR)
        .setHasAnnotationProcessing(false);

    LibraryJarCommand.Builder libraryJarBuilder = LibraryJarCommand.newBuilder();
    LibraryJarBaseCommand.Builder libraryJarBaseCommandBuilder =
        libraryJarBuilder.getLibraryJarBaseCommandBuilder();
    libraryJarBaseCommandBuilder
        .setPathToClasses(RelPath.newBuilder().setPath("output/lib__path__output/path.jar").build())
        .setRootOutput(RelPath.newBuilder().setPath("output/lib__path__output").build())
        .setPathToClassHashes(RelPath.newBuilder().setPath("output/path.classes.txt").build())
        .setAnnotationsPath(RelPath.newBuilder().setPath("output/__path_gen__").build());

    return buildJavaCommandBuilder
        .setLibraryJarCommand(
            libraryJarBuilder
                .setBaseJarCommand(
                    buildBaseJarCommand(
                        target,
                        baseDirectory,
                        outputPathDir,
                        sourceAbiOutputPathDir,
                        sourceOnlyAbiOutputPathDir))
                .build())
        .build();
  }

  private BaseJarCommand buildBaseJarCommand(
      String target,
      String baseDirectory,
      String outputPathDir,
      String sourceAbiOutputPathDir,
      String sourceOnlyAbiOutputPathDir) {
    return BaseJarCommand.newBuilder()
        .setAbiCompatibilityMode(AbiGenerationMode.SOURCE_ONLY)
        .setAbiGenerationMode(AbiGenerationMode.SOURCE_ONLY)
        .setTrackClassUsage(true)
        .setFilesystemParams(
            FilesystemParams.newBuilder()
                .setRootPath(
                    com.facebook.buck.javacd.model.AbsPath.newBuilder()
                        .setPath(baseDirectory)
                        .build())
                .setConfiguredBuckOut(RelPath.newBuilder().setPath("buck-out").build())
                .addGlobIgnorePaths("ant-out/**")
                .addGlobIgnorePaths("**/*.pyc")
                .build())
        .setBuildTargetValue(
            BuildTargetValue.newBuilder()
                .setFullyQualifiedName(target)
                .setType(BuildTargetValue.Type.LIBRARY)
                .build())
        .setOutputPathsValue(
            OutputPathsValue.newBuilder()
                .setLibraryPaths(
                    OutputPathsValue.OutputPaths.newBuilder()
                        .setClassesDir(
                            RelPath.newBuilder()
                                .setPath(outputPathDir + "/lib__path__scratch/classes")
                                .build())
                        .setOutputJarDirPath(
                            RelPath.newBuilder()
                                .setPath(outputPathDir + "/lib__path__output")
                                .build())
                        .setAnnotationPath(
                            RelPath.newBuilder().setPath(outputPathDir + "/__path_gen__").build())
                        .setPathToSourcesList(
                            RelPath.newBuilder().setPath(outputPathDir + "/__path__srcs").build())
                        .setWorkingDirectory(
                            RelPath.newBuilder()
                                .setPath(outputPathDir + "/lib__path__working_directory")
                                .build())
                        .setOutputJarPath(
                            RelPath.newBuilder()
                                .setPath(outputPathDir + "/lib__path__output/path.jar")
                                .build())
                        .build())
                .setSourceAbiPaths(
                    OutputPathsValue.OutputPaths.newBuilder()
                        .setClassesDir(
                            RelPath.newBuilder()
                                .setPath(sourceAbiOutputPathDir + "/lib__path__scratch/classes")
                                .build())
                        .setOutputJarDirPath(
                            RelPath.newBuilder()
                                .setPath(sourceAbiOutputPathDir + "/lib__path__output")
                                .build())
                        .setAnnotationPath(
                            RelPath.newBuilder()
                                .setPath(sourceAbiOutputPathDir + "/__path_gen__")
                                .build())
                        .setPathToSourcesList(
                            RelPath.newBuilder()
                                .setPath(sourceAbiOutputPathDir + "/__path__srcs")
                                .build())
                        .setWorkingDirectory(
                            RelPath.newBuilder()
                                .setPath(sourceAbiOutputPathDir + "/lib__path__working_directory")
                                .build())
                        .setOutputJarPath(
                            RelPath.newBuilder()
                                .setPath(sourceAbiOutputPathDir + "/lib__path__output/path.jar")
                                .build())
                        .build())
                .setSourceOnlyAbiPaths(
                    OutputPathsValue.OutputPaths.newBuilder()
                        .setClassesDir(
                            RelPath.newBuilder()
                                .setPath(sourceOnlyAbiOutputPathDir + "/lib__path__scratch/classes")
                                .build())
                        .setOutputJarDirPath(
                            RelPath.newBuilder()
                                .setPath(sourceOnlyAbiOutputPathDir + "/lib__path__output")
                                .build())
                        .setAnnotationPath(
                            RelPath.newBuilder()
                                .setPath(sourceOnlyAbiOutputPathDir + "/__path_gen__")
                                .build())
                        .setPathToSourcesList(
                            RelPath.newBuilder()
                                .setPath(sourceOnlyAbiOutputPathDir + "/__path__srcs")
                                .build())
                        .setWorkingDirectory(
                            RelPath.newBuilder()
                                .setPath(
                                    sourceOnlyAbiOutputPathDir + "/lib__path__working_directory")
                                .build())
                        .setOutputJarPath(
                            RelPath.newBuilder()
                                .setPath(sourceOnlyAbiOutputPathDir + "/lib__path__output/path.jar")
                                .build())
                        .build())
                .build())
        .addCompileTimeClasspathPaths(
            RelPath.newBuilder().setPath("libs/exceptions-abi.jar").build())
        .addCompileTimeClasspathPaths(
            RelPath.newBuilder().setPath("libs/filesystems-abi.jar").build())
        .addCompileTimeClasspathPaths(RelPath.newBuilder().setPath("libs/platform-abi.jar").build())
        .addCompileTimeClasspathPaths(RelPath.newBuilder().setPath("libs/guava-abi.jar").build())
        .addCompileTimeClasspathPaths(RelPath.newBuilder().setPath("libs/jsr305-abi.jar").build())
        .addJavaSrcs(
            RelPath.newBuilder()
                .setPath("src/com/facebook/buck/core/path/TestForwardRelativePath.java")
                .build())
        .addJavaSrcs(
            RelPath.newBuilder()
                .setPath("src/com/facebook/buck/core/path/TestGenruleOutPath.java")
                .build())
        .addFullJarInfos(
            JavaAbiInfo.newBuilder()
                .setBuildTargetName("//src/com/facebook/buck/core/exceptions:exceptions")
                .build())
        .addFullJarInfos(
            JavaAbiInfo.newBuilder()
                .setBuildTargetName("//src/com/facebook/buck/core/filesystems:filesystems")
                .build())
        .addFullJarInfos(
            JavaAbiInfo.newBuilder()
                .setBuildTargetName("//src/com/facebook/buck/util/environment:platform")
                .build())
        .addFullJarInfos(
            JavaAbiInfo.newBuilder().setBuildTargetName("//third-party/java/guava:guava").build())
        .addFullJarInfos(
            JavaAbiInfo.newBuilder().setBuildTargetName("//third-party/java/jsr:jsr305").build())
        .addAbiJarInfos(
            JavaAbiInfo.newBuilder().setBuildTargetName("//third-party/java/guava:guava").build())
        .addAbiJarInfos(
            JavaAbiInfo.newBuilder().setBuildTargetName("//third-party/java/jsr:jsr305").build())
        .putCellToPathMappings("", RelPath.newBuilder().setPath("").build())
        .setLibraryJarParameters(
            JarParameters.newBuilder()
                .setJarPath(
                    RelPath.newBuilder().setPath("output/lib__path__output/path.jar").build())
                .addEntriesToJar(
                    RelPath.newBuilder().setPath("output/lib__path__scratch/classes").build())
                .setDuplicatesLogLevel(JarParameters.LogLevel.INFO)
                .build())
        .setBuildCellRootPath(
            com.facebook.buck.javacd.model.AbsPath.newBuilder().setPath(baseDirectory).build())
        .setResolvedJavac(
            ResolvedJavac.newBuilder()
                .setJcr199Javac(ResolvedJavac.JSR199Javac.getDefaultInstance())
                .build())
        .setResolvedJavacOptions(
            ResolvedJavacOptions.newBuilder()
                .addBootclasspathList(
                    RelPath.newBuilder().setPath("third-party/java/jdk/jdk8-rt-stub.jar").build())
                .setLanguageLevelOptions(
                    ResolvedJavacOptions.JavacLanguageLevelOptions.newBuilder()
                        .setSourceLevel("8")
                        .setTargetLevel("8")
                        .build())
                .setDebug(true)
                .build())
        .build();
  }

  private ImmutableList<String> getLaunchJavaCDCommand() throws IOException {
    return ImmutableList.<String>builder()
        .add(JavaRuntimeUtils.getBucksJavaBinCommand())
        .addAll(
            JavaCDWorkerStepUtils.getCommonJvmParams(
                AbsPath.of(temporaryFolder.newFolder("log").getPath())))
        .add("-cp")
        .add(testBinary.toString())
        .add(BuckClasspath.BOOTSTRAP_MAIN_CLASS)
        .add(JavaCDWorkerStepUtils.JAVACD_MAIN_CLASS)
        .build();
  }

  private ImmutableMap<String, String> getEnvs() {
    return ImmutableMap.of(BuckClasspath.ENV_VAR_NAME, testBinary.toString());
  }

  private void waitTillEventsProcessed() throws InterruptedException {
    ExecutorServiceUtils.waitTillAllTasksCompleted(
        (ThreadPoolExecutor) DownwardApiProcessExecutor.HANDLER_THREAD_POOL);
  }
}
