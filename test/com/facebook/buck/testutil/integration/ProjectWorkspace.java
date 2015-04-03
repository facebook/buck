/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.testutil.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.cli.Main;
import com.facebook.buck.cli.TestCommand;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.MoreStrings;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;
import com.google.common.io.Files;
import com.martiansoftware.nailgun.NGContext;

import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import javax.annotation.Nullable;

/**
 * {@link ProjectWorkspace} is a directory that contains a Buck project, complete with build files.
 * <p>
 * When {@link #setUp()} is invoked, the project files are cloned from a directory of testdata into
 * a tmp directory according to the following rule:
 * <ul>
 *   <li>Files with the {@code .expected} extension will not be copied.
 * </ul>
 * After {@link #setUp()} is invoked, the test should invoke Buck in that directory. As this is an
 * integration test, we expect that files will be written as a result of invoking Buck.
 * <p>
 * After Buck has been run, invoke {@link #verify()} to verify that Buck wrote the correct files.
 * For each file in the testdata directory with the {@code .expected} extension, {@link #verify()}
 * will check that a file with the same relative path (but without the {@code .expected} extension)
 * exists in the tmp directory. If not, {@link org.junit.Assert#fail()} will be invoked.
 */
public class ProjectWorkspace {

  private static final String EXPECTED_SUFFIX = ".expected";

  private static final String PATH_TO_BUILD_LOG = "buck-out/bin/build.log";

  private static final Function<Path, Path> BUILD_FILE_RENAME = new Function<Path, Path>() {
    @Override
    @Nullable
    public Path apply(Path path) {
      String fileName = path.getFileName().toString();
      if (fileName.endsWith(EXPECTED_SUFFIX)) {
        return null;
      } else {
        return path;
      }
    }
  };

  private boolean isSetUp = false;
  private final Path templatePath;
  private final Path destPath;

  /**
   * @param templateDir The directory that contains the template version of the project.
   * @param temporaryFolder The directory where the clone of the template directory should be
   *     written. By requiring a {@link TemporaryFolder} rather than a {@link File}, we can ensure
   *     that JUnit will clean up the test correctly.
   */
  public ProjectWorkspace(Path templateDir, DebuggableTemporaryFolder temporaryFolder) {
    Preconditions.checkNotNull(templateDir);
    Preconditions.checkNotNull(temporaryFolder);
    this.templatePath = templateDir;
    this.destPath = temporaryFolder.getRoot().toPath();
  }

  public ProjectWorkspace(File templateDir, DebuggableTemporaryFolder temporaryFolder) {
    this(templateDir.toPath(), temporaryFolder);
  }

  /**
   * This will copy the template directory, renaming files named {@code BUCK.test} to {@code BUCK}
   * in the process. Files whose names end in {@code .expected} will not be copied.
   */
  public ProjectWorkspace setUp() throws IOException {

    MoreFiles.copyRecursively(templatePath, destPath, BUILD_FILE_RENAME);

    // If there's a local.properties in the host project but not in the destination, make a copy.
    Path localProperties = FileSystems.getDefault().getPath("local.properties");
    Path destLocalProperties = destPath.resolve(localProperties.getFileName());

    if (localProperties.toFile().exists() && !destLocalProperties.toFile().exists()) {
      java.nio.file.Files.copy(localProperties, destLocalProperties);
    }

    if (Platform.detect() == Platform.WINDOWS) {
      // Hack for symlinks on Windows.
      SimpleFileVisitor<Path> copyDirVisitor = new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
          // On Windows, symbolic links from git repository are checked out as normal files
          // containing a one-line path. In order to distinguish them, paths are read and pointed
          // files are trued to locate. Once the pointed file is found, it will be copied to target.
          // On NTFS length of path must be greater than 0 and less than 4096.
          if (attrs.size() > 0 && attrs.size() <= 4096) {
            File file = path.toFile();
            String linkTo = Files.toString(file, Charsets.UTF_8);
            File linkToFile = new File(templatePath.toFile(), linkTo);
            if (linkToFile.isFile()) {
              java.nio.file.Files.copy(
                  linkToFile.toPath(), path, StandardCopyOption.REPLACE_EXISTING);
            } else if (linkToFile.isDirectory()) {
              if (!file.delete()) {
                throw new IOException();
              }
              MoreFiles.copyRecursively(linkToFile.toPath(), path);
            }
          }
          return FileVisitResult.CONTINUE;
        }
      };
      java.nio.file.Files.walkFileTree(destPath, copyDirVisitor);
    }
    isSetUp = true;
    return this;
  }

  public ProcessResult runBuckBuild(String... args) throws IOException {
    String[] totalArgs = new String[args.length + 1];
    totalArgs[0] = "build";
    System.arraycopy(args, 0, totalArgs, 1, args.length);
    return runBuckCommand(totalArgs);
  }

  public File buildAndReturnOutput(String target) throws IOException {
    // Build the java_library.
    ProjectWorkspace.ProcessResult buildResult = runBuckBuild(target.toString());
    buildResult.assertSuccess();

    // Use `buck targets` to find the output JAR file.
    // TODO(jacko): This is going to overwrite the build.log. Maybe stash that and return it?
    ProjectWorkspace.ProcessResult outputFileResult = runBuckCommand(
        "targets",
        "--show_output",
        target.toString());
    outputFileResult.assertSuccess();
    String pathToGeneratedJarFile = outputFileResult.getStdout().split(" ")[1].trim();
    return getFile(pathToGeneratedJarFile);
  }

  public ProcessExecutor.Result runJar(File jar, String... args)
      throws IOException, InterruptedException {
    List<String> command = ImmutableList.<String>builder()
        .add("java")
        .add("-jar")
        .add(jar.toString())
        .addAll(ImmutableList.copyOf(args))
        .build();
    return doRunCommand(command);
  }

  public ProcessExecutor.Result runCommand(String exe, String... args)
      throws IOException, InterruptedException {
    List<String> command = ImmutableList.<String>builder()
        .add(exe)
        .addAll(ImmutableList.copyOf(args))
        .build();
    return doRunCommand(command);
  }

  private ProcessExecutor.Result doRunCommand(List<String> command)
      throws IOException, InterruptedException {
    String[] commandArray = command.toArray(new String[command.size()]);
    Process process = Runtime.getRuntime().exec(commandArray);
    ProcessExecutor executor = new ProcessExecutor(new TestConsole());
    String currentDir = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", destPath.toAbsolutePath().toString());
      return executor.execute(process);
    } finally {
      System.setProperty("user.dir", currentDir);
    }
  }

  /**
   * Runs Buck with the specified list of command-line arguments.
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *   {@code ["project"]}, etc.
   * @return the result of running Buck, which includes the exit code, stdout, and stderr.
   */
  public ProcessResult runBuckCommand(String... args)
      throws IOException {
    return runBuckCommandWithEnvironmentAndContext(
        Optional.<NGContext>absent(),
        Optional.<BuckEventListener>absent(),
        args);
  }

  public ProcessResult runBuckdCommand(String... args) throws IOException {
    try (TestContext context = new TestContext()) {
      return runBuckdCommand(context, args);
    }
  }

  public ProcessResult runBuckdCommand(ImmutableMap<String, String> environment, String... args)
      throws IOException {
    try (TestContext context = new TestContext(environment)) {
      return runBuckdCommand(context, args);
    }
  }

  public ProcessResult runBuckdCommand(NGContext context, String... args)
      throws IOException {
    return runBuckCommandWithEnvironmentAndContext(
        Optional.of(context),
        Optional.<BuckEventListener>absent(),
        args);
  }

  public ProcessResult runBuckdCommand(
      NGContext context,
      BuckEventListener eventListener,
      String... args)
      throws IOException {
    return runBuckCommandWithEnvironmentAndContext(
        Optional.of(context),
        Optional.of(eventListener),
        args);
  }

  private ProcessResult runBuckCommandWithEnvironmentAndContext(
      Optional<NGContext> context,
      Optional<BuckEventListener> eventListener,
      String... args)
    throws IOException {
    assertTrue("setUp() must be run before this method is invoked", isSetUp);
    CapturingPrintStream stdout = new CapturingPrintStream();
    CapturingPrintStream stderr = new CapturingPrintStream();

    final ImmutableList.Builder<BuckEvent> capturedEventsListBuilder =
        new ImmutableList.Builder<>();
    BuckEventListener capturingEventListener = new BuckEventListener() {
      @Subscribe
      public void captureEvent(BuckEvent event) {
        capturedEventsListBuilder.add(event);
      }

      @Override
      public void outputTrace(BuildId buildId) throws InterruptedException {
        // empty
      }
    };
    ImmutableList.Builder<BuckEventListener> eventListeners = ImmutableList.builder();
    eventListeners.add(capturingEventListener);
    if (eventListener.isPresent()) {
      eventListeners.add(eventListener.get());
    }

    // Construct a limited view of the parent environment for the child.
    //
    // TODO(#5754812): we should eventually get tests working without requiring these be set.
    ImmutableList<String> inheritedEnvVars = ImmutableList.of(
        "ANDROID_HOME",
        "ANDROID_NDK",
        "ANDROID_NDK_REPOSITORY",
        "ANDROID_SDK",
        "PATH",
        "PATHEXT",

        // Needed by ndk-build on Windows
        "OS",
        "ProgramW6432",
        "ProgramFiles(x86)",

        // TODO(#6586154): set TMP variable for ShellSteps
        "TMP");
    ImmutableMap.Builder<String, String> envBuilder = ImmutableMap.builder();
    for (String variable : inheritedEnvVars) {
      String value = System.getenv(variable);
      if (value != null) {
        envBuilder.put(variable, value);
      }
    }
    ImmutableMap<String, String> env = envBuilder.build();

    Main main = new Main(stdout, stderr, eventListeners.build());
    int exitCode = 0;
    try {
      exitCode = main.runMainWithExitCode(
          new BuildId(),
          destPath,
          context,
          env,
          args);
    } catch (InterruptedException e) {
      e.printStackTrace(stderr);
      exitCode = Main.FAIL_EXIT_CODE;
      Thread.currentThread().interrupt();
    }

    return new ProcessResult(exitCode,
        stdout.getContentsAsString(Charsets.UTF_8),
        stderr.getContentsAsString(Charsets.UTF_8),
        capturedEventsListBuilder.build());
  }

  /**
   * @return the {@link File} that corresponds to the {@code pathRelativeToProjectRoot}.
   */
  public File getFile(String pathRelativeToProjectRoot) {
    return getPath(pathRelativeToProjectRoot).toFile();
  }

  public Path getPath(String pathRelativeToProjectRoot) {
    return destPath.resolve(pathRelativeToProjectRoot);
  }

  public String getFileContents(String pathRelativeToProjectRoot) throws IOException {
    return Files.toString(getFile(pathRelativeToProjectRoot), Charsets.UTF_8);
  }

  public void enableDirCache() throws IOException {
    writeContentsToPath("[cache]\n  mode = dir", ".buckconfig.local");
  }

  public void copyFile(String source, String dest) throws IOException {
    Files.copy(getFile(source), getFile(dest));
  }

  public void move(String source, String dest) throws IOException {
    Files.move(getFile(source), getFile(dest));
  }

  public void replaceFileContents(String pathRelativeToProjectRoot,
      String target,
      String replacement) throws IOException {
    String fileContents = getFileContents(pathRelativeToProjectRoot);
    fileContents = fileContents.replace(target, replacement);
    writeContentsToPath(fileContents, pathRelativeToProjectRoot);
  }

  public void writeContentsToPath(String contents, String pathRelativeToProjectRoot)
      throws IOException {
    Files.write(contents.getBytes(Charsets.UTF_8), getFile(pathRelativeToProjectRoot));
  }

  /**
   * @return the specified path resolved against the root of this workspace.
   */
  public Path resolve(Path pathRelativeToWorkspaceRoot) {
    return destPath.resolve(pathRelativeToWorkspaceRoot);
  }

  public Path resolve(String pathRelativeToWorkspaceRoot) {
    return destPath.resolve(pathRelativeToWorkspaceRoot);
  }

  public void resetBuildLogFile() throws IOException {
    writeContentsToPath("", PATH_TO_BUILD_LOG);
  }

  public BuckBuildLog getBuildLog() throws IOException {
    return BuckBuildLog.fromLogContents(
        Files.readLines(getFile(PATH_TO_BUILD_LOG), Charsets.UTF_8));
  }

  /** The result of running {@code buck} from the command line. */
  public static class ProcessResult {
    private final int exitCode;
    private final List<BuckEvent> capturedEvents;
    private final String stdout;
    private final String stderr;

    private ProcessResult(
        int exitCode,
        String stdout,
        String stderr,
        List<BuckEvent> capturedEvents) {
      this.exitCode = exitCode;
      this.stdout = Preconditions.checkNotNull(stdout);
      this.stderr = Preconditions.checkNotNull(stderr);
      this.capturedEvents = capturedEvents;
    }

    /**
     * Returns the exit code from the process.
     * <p>
     * Currently, this method is private because, in practice, any time a client might want to use
     * it, it is more appropriate to use {@link #assertSuccess()} or
     * {@link #assertFailure()} instead. If a valid use case arises, then we should make this
     * getter public.
     */
    private int getExitCode() {
      return exitCode;
    }

    public String getStdout() {
      return stdout;
    }

    public String getStderr() {
      return stderr;
    }

    public List<BuckEvent> getCapturedEvents() {
      return capturedEvents;
    }

    public ProcessResult assertSuccess() {
      return assertExitCode(null, 0);
    }

    public ProcessResult assertSuccess(String message) {
      return assertExitCode(message, 0);
    }

    public ProcessResult assertFailure() {
      return assertExitCode(null, Main.FAIL_EXIT_CODE);
    }

    public ProcessResult assertTestFailure() {
      return assertExitCode(null, TestCommand.TEST_FAILURES_EXIT_CODE);
    }

    public ProcessResult assertTestFailure(String message) {
      return assertExitCode(message, TestCommand.TEST_FAILURES_EXIT_CODE);
    }

    public ProcessResult assertFailure(String message) {
      return assertExitCode(message, 1);
    }

    private ProcessResult assertExitCode(@Nullable String message, int exitCode) {
      if (exitCode == getExitCode()) {
        return this;
      }

      String failureMessage = String.format(
          "Expected exit code %d but was %d.", exitCode, getExitCode());
      if (message != null) {
        failureMessage = message + " " + failureMessage;
      }

      System.err.println("=== " + failureMessage + " ===");
      System.err.println("=== STDERR ===");
      System.err.println(getStderr());
      System.err.println("=== STDOUT ===");
      System.err.println(getStdout());
      fail(failureMessage);
      return this;
    }

    public ProcessResult assertSpecialExitCode(String message, int exitCode) {
      return assertExitCode(message, exitCode);
    }
  }

  /**
   * For every file in the template directory whose name ends in {@code .expected}, checks that an
   * equivalent file has been written in the same place under the destination directory.
   */
  public void verify() throws IOException {
    SimpleFileVisitor<Path> copyDirVisitor = new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        String fileName = file.getFileName().toString();
        if (fileName.endsWith(EXPECTED_SUFFIX)) {
          // Get File for the file that should be written, but without the ".expected" suffix.
          Path generatedFileWithSuffix = destPath.resolve(templatePath.relativize(file));
          File directory = generatedFileWithSuffix.getParent().toFile();
          File observedFile = new File(directory, Files.getNameWithoutExtension(fileName));

          if (!observedFile.isFile()) {
            fail("Expected file " + observedFile + " could not be found.");
          }
          String expectedFileContent = Files.toString(file.toFile(), Charsets.UTF_8);
          String observedFileContent = Files.toString(observedFile, Charsets.UTF_8);
          // It is possible, on Windows, to have Git keep "\n"-style newlines, or convert them to
          // "\r\n"-style newlines.  Support both ways by normalizing to "\n"-style newlines.
          // See https://help.github.com/articles/dealing-with-line-endings/ for more information.
          expectedFileContent = expectedFileContent.replace("\r\n", "\n");
          observedFileContent = observedFileContent.replace("\r\n", "\n");
          String cleanPathToObservedFile = MoreStrings.withoutSuffix(
              templatePath.relativize(file).toString(), EXPECTED_SUFFIX);
          assertEquals(
              String.format(
                  "In %s, expected content of %s to match that of %s.",
                  cleanPathToObservedFile,
                  expectedFileContent,
                  observedFileContent),
              expectedFileContent,
              observedFileContent);
        }
        return FileVisitResult.CONTINUE;
      }
    };
    java.nio.file.Files.walkFileTree(templatePath, copyDirVisitor);
  }
}
