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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.facebook.buck.android.DefaultAndroidDirectoryResolver;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.Main;
import com.facebook.buck.cli.TestRunning;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.ConfigConfig;
import com.facebook.buck.config.Configs;
import com.facebook.buck.config.RawConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.jvm.java.JavaCompilationConstants;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.KnownBuildRuleTypesFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.DefaultPropertyFinder;
import com.facebook.buck.util.MoreStrings;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.martiansoftware.nailgun.NGContext;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import javax.annotation.Nullable;

/**
 * {@link ProjectWorkspace} is a directory that contains a Buck project, complete with build files.
 * <p>
 * When {@link #setUp()} is invoked, the project files are cloned from a directory of testdata into
 * a tmp directory according to the following rule:
 * <ul>
 *   <li>Files with the {@code .fixture} extension will be copied and renamed without the extension.
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

  private static final String FIXTURE_SUFFIX = "fixture";
  private static final String EXPECTED_SUFFIX = "expected";

  private static final String PATH_TO_BUILD_LOG = "buck-out/bin/build.log";

  private static final Function<Path, Path> BUILD_FILE_RENAME = new Function<Path, Path>() {
    @Override
    @Nullable
    public Path apply(Path path) {
      String fileName = path.getFileName().toString();
      String extension = com.google.common.io.Files.getFileExtension(fileName);
      switch (extension) {
        case FIXTURE_SUFFIX:
          return path.getParent().resolve(
              com.google.common.io.Files.getNameWithoutExtension(fileName));
        case EXPECTED_SUFFIX:
          return null;
        default:
          return path;
      }
    }
  };

  private boolean isSetUp = false;
  private final Path templatePath;
  private final Path destPath;

  @VisibleForTesting
  ProjectWorkspace(Path templateDir, Path targetFolder) {
    this.templatePath = templateDir;
    this.destPath = targetFolder;
  }

  /**
   * Creates a new workspace by copying the contents of existingWorkspace to targetFolder when
   * {@link #setUp()} is invoked. In general, prefer
   * {@link TestDataHelper#createProjectWorkspaceForScenario(Object, String, TemporaryRoot)} to this
   * method.
   * <p>
   * A valid reason to use this API is for performance reasons. Specifically, it may be expensive to
   * put a workspace into a particular state. If you have various scenarios that you want to test
   * based on that state, then it makes sense to create the initial state once in
   * {@code @BeforeClass} and then use this method to create a new workspace for the scenario you
   * are about to test in {@code @Before}.
   *
   * @param existingWorkspace The directory that contains the template version of the project.
   * @param targetFolder The directory where the clone of the template directory should be
   *     written. By requiring a {@link TemporaryRoot} rather than a {@link File}, we can ensure
   *     that JUnit will clean up the test correctly.
   */
  public static ProjectWorkspace cloneExistingWorkspaceIntoNewFolder(
      ProjectWorkspace existingWorkspace,
      TemporaryRoot targetFolder) {
    return new ProjectWorkspace(existingWorkspace.getDestPath(), targetFolder.getRootPath());
  }

  /**
   * This will copy the template directory, renaming files named {@code foo.fixture} to {@code foo}
   * in the process. Files whose names end in {@code .expected} will not be copied.
   */
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public ProjectWorkspace setUp() throws IOException {

    MoreFiles.copyRecursively(templatePath, destPath, BUILD_FILE_RENAME);

    // Stamp the buck-out directory if it exists and isn't stamped already
    try (OutputStream outputStream =
             new BufferedOutputStream(
                 Channels.newOutputStream(
                     Files.newByteChannel(
                         destPath.resolve(BuckConstant.getCurrentVersionFile()),
                         ImmutableSet.<OpenOption>of(
                             StandardOpenOption.CREATE_NEW,
                             StandardOpenOption.WRITE))))) {
      outputStream.write(BuckVersion.getVersion().getBytes(Charsets.UTF_8));
    } catch (FileAlreadyExistsException | NoSuchFileException e) {
      // If the current version file already exists we don't need to create it
      // If buck-out doesn't exist we don't need to stamp it
    }

    // If there's a local.properties in the host project but not in the destination, make a copy.
    Path localProperties = FileSystems.getDefault().getPath("local.properties");
    Path destLocalProperties = destPath.resolve(localProperties.getFileName());

    if (localProperties.toFile().exists() && !destLocalProperties.toFile().exists()) {
      Files.copy(localProperties, destLocalProperties);
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
            String linkTo = new String(Files.readAllBytes(path), UTF_8);
            Path linkToFile;
            try {
              linkToFile = templatePath.resolve(linkTo);
            } catch (InvalidPathException e) {
              // Let's assume we were reading a normal text file, and not something meant to be a
              // link.
              return FileVisitResult.CONTINUE;
            }
            if (Files.isRegularFile(linkToFile)) {
              Files.copy(linkToFile, path, StandardCopyOption.REPLACE_EXISTING);
            } else if (Files.isDirectory(linkToFile)) {
              Files.delete(path);
              MoreFiles.copyRecursively(linkToFile, path);
            }
          }
          return FileVisitResult.CONTINUE;
        }
      };
      Files.walkFileTree(destPath, copyDirVisitor);
    }

    // Disable the directory cache by default.  Tests that want to enable it can call
    // `enableDirCache` on this object.  Only do this if a .buckconfig.local file does not already
    // exist, however (we assume the test knows what it is doing at that point).
    if (!Files.exists(getPath(".buckconfig.local"))) {
      writeContentsToPath("[cache]\n  mode =", ".buckconfig.local");
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

  public Path buildAndReturnOutput(String... args) throws IOException {
    // Build the java_library.
    ProjectWorkspace.ProcessResult buildResult = runBuckBuild(args);
    buildResult.assertSuccess();

    // Use `buck targets` to find the output JAR file.
    // TODO(oconnor663): This is going to overwrite the build.log. Maybe stash that and return it?
    ProjectWorkspace.ProcessResult outputFileResult = runBuckCommand(
        "targets",
        "--show-output",
        args[args.length - 1]);
    outputFileResult.assertSuccess();
    String pathToGeneratedJarFile = outputFileResult.getStdout().split(" ")[1].trim();
    return getPath(pathToGeneratedJarFile);
  }

  public ProcessExecutor.Result runJar(Path jar,
      ImmutableList<String> vmArgs,
      String... args)  throws IOException, InterruptedException {
    List<String> command = ImmutableList.<String>builder()
        .add(JavaCompilationConstants.DEFAULT_JAVA_OPTIONS.getJavaRuntimeLauncher().getCommand())
        .addAll(vmArgs)
        .add("-jar")
        .add(jar.toString())
        .addAll(ImmutableList.copyOf(args))
        .build();
    return doRunCommand(command);
  }

  public ProcessExecutor.Result runJar(Path jar, String... args)
      throws IOException, InterruptedException {
    return runJar(jar, ImmutableList.<String>of(), args);
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
        destPath,
        Optional.<NGContext>absent(),
        Optional.<ImmutableMap<String, String>>absent(),
        args);
  }

  public ProcessResult runBuckCommand(Path repoRoot, String... args)
      throws IOException {
    return runBuckCommandWithEnvironmentAndContext(
        repoRoot,
        Optional.<NGContext>absent(),
        Optional.<ImmutableMap<String, String>>absent(),
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

  public ProcessResult runBuckdCommand(NGContext context, String... args) throws IOException {
    return runBuckdCommand(context, new CapturingPrintStream(), args);
  }

  public ProcessResult runBuckdCommand(
      NGContext context,
      CapturingPrintStream stderr,
      String... args)
      throws IOException {
    assumeTrue(
        "watchman must exist to run buckd",
        new ExecutableFinder(Platform.detect()).getOptionalExecutable(
            Paths.get("watchman"),
            ImmutableMap.copyOf(System.getenv())).isPresent());
    return runBuckCommandWithEnvironmentAndContext(
        destPath,
        Optional.of(context),
        Optional.<ImmutableMap<String, String>>absent(),
        stderr,
        args);
  }

  public ProcessResult runBuckCommandWithEnvironmentAndContext(
      Path repoRoot,
      Optional<NGContext> context,
      Optional<ImmutableMap<String, String>> env,
      String... args)
      throws IOException {
    return runBuckCommandWithEnvironmentAndContext(
        repoRoot,
        context,
        env,
        new CapturingPrintStream(),
        args);
  }

  public ProcessResult runBuckCommandWithEnvironmentAndContext(
      Path repoRoot,
      Optional<NGContext> context,
      Optional<ImmutableMap<String, String>> env,
      CapturingPrintStream stderr,
      String... args)
    throws IOException {
    assertTrue("setUp() must be run before this method is invoked", isSetUp);
    CapturingPrintStream stdout = new CapturingPrintStream();
    InputStream stdin = new ByteArrayInputStream("".getBytes());

    // Construct a limited view of the parent environment for the child.
    //
    // TODO(#5754812): we should eventually get tests working without requiring these be set.
    ImmutableList<String> inheritedEnvVars = ImmutableList.of(
        "ANDROID_HOME",
        "ANDROID_NDK",
        "ANDROID_NDK_REPOSITORY",
        "ANDROID_SDK",
        // TODO(grumpyjames) Write an equivalent of the groovyc and startGroovy
        // scripts provided by the groovy distribution in order to remove these two.
        "GROOVY_HOME",
        "JAVA_HOME",

        "NDK_HOME",
        "PATH",
        "PATHEXT",

        // Needed by ndk-build on Windows
        "OS",
        "ProgramW6432",
        "ProgramFiles(x86)",

        // The haskell integration tests call into GHC, which needs HOME to be set.
        "HOME",

        // TODO(#6586154): set TMP variable for ShellSteps
        "TMP");
    ImmutableMap.Builder<String, String> envBuilder = ImmutableMap.builder();
    for (String variable : inheritedEnvVars) {
      String value = System.getenv(variable);
      if (value != null) {
        envBuilder.put(variable, value);
      }
    }
    ImmutableMap<String, String> sanizitedEnv = envBuilder.build();

    Main main = new Main(stdout, stderr, stdin);
    int exitCode;
    try {
      exitCode = main.runMainWithExitCode(
          new BuildId(),
          repoRoot,
          context,
          env.or(sanizitedEnv),
          /* setupLogging */ false,
          /* readGlobalConfigs */ false,
          args);
    } catch (InterruptedException e) {
      e.printStackTrace(stderr);
      exitCode = Main.FAIL_EXIT_CODE;
      Thread.currentThread().interrupt();
    }

    return new ProcessResult(
        exitCode,
        stdout.getContentsAsString(Charsets.UTF_8),
        stderr.getContentsAsString(Charsets.UTF_8));
  }

  public Path getDestPath() {
    return destPath;
  }

  public Path getPath(Path pathRelativeToProjectRoot) {
    return destPath.resolve(pathRelativeToProjectRoot);
  }

  public Path getPath(String pathRelativeToProjectRoot) {
    return destPath.resolve(pathRelativeToProjectRoot);
  }

  public String getFileContents(Path pathRelativeToProjectRoot) throws IOException {
    return getFileContentsWithAbsolutePath(getPath(pathRelativeToProjectRoot));
  }

  public String getFileContents(String pathRelativeToProjectRoot) throws IOException {
    return getFileContentsWithAbsolutePath(getPath(pathRelativeToProjectRoot));
  }

  private String getFileContentsWithAbsolutePath(Path path) throws IOException {
    String platformExt = null;
    switch (Platform.detect()) {
      case LINUX:
        platformExt = "linux";
        break;
      case MACOS:
        platformExt = "macos";
        break;
      case WINDOWS:
        platformExt = "win";
        break;
      case UNKNOWN:
        // Leave platformExt as null.
        break;
    }
    if (platformExt != null) {
      String extension = com.google.common.io.Files.getFileExtension(path.toString());
      String basename = com.google.common.io.Files.getNameWithoutExtension(path.toString());
      Path platformPath = extension.length() > 0 ?
          path.getParent().resolve(String.format("%s.%s.%s", basename, platformExt, extension)) :
          path.getParent().resolve(String.format("%s.%s", basename, platformExt));
      if (platformPath.toFile().exists()) {
        path = platformPath;
      }
    }
    return new String(Files.readAllBytes(path), UTF_8);
  }

  public void enableDirCache() throws IOException {
    writeContentsToPath("[cache]\n  mode = dir", ".buckconfig.local");
  }

  public void copyFile(String source, String dest) throws IOException {
    Path destination = getPath(dest);
    Files.deleteIfExists(destination);
    Files.copy(getPath(source), destination);
  }

  public void copyRecursively(Path source, Path pathRelativeToProjectRoot) throws IOException {
    MoreFiles.copyRecursively(source, destPath.resolve(pathRelativeToProjectRoot));
  }

  public void move(String source, String dest) throws IOException {
    Files.move(getPath(source), getPath(dest));
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
    Files.write(getPath(pathRelativeToProjectRoot), contents.getBytes(UTF_8));
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
        getDestPath(),
        Files.readAllLines(getPath(PATH_TO_BUILD_LOG), UTF_8));
  }

  public Cell asCell() throws IOException, InterruptedException {
    Config config = Configs.createConfig(
        ConfigConfig.of(false, Optional.of(getDestPath()), RawConfig.of()));

    ProjectFilesystem filesystem = new ProjectFilesystem(getDestPath(), config);
    TestConsole console = new TestConsole();
    ImmutableMap<String, String> env = ImmutableMap.copyOf(System.getenv());
    DefaultAndroidDirectoryResolver directoryResolver = new DefaultAndroidDirectoryResolver(
        filesystem,
        Optional.<String>absent(),
        Optional.<String>absent(),
        new DefaultPropertyFinder(filesystem, env));
    return Cell.createCell(
        filesystem,
        console,
        Watchman.NULL_WATCHMAN,
        new BuckConfig(config, filesystem, Architecture.detect(), Platform.detect(), env),
        new KnownBuildRuleTypesFactory(
            new ProcessExecutor(console),
            directoryResolver,
            Optional.<Path>absent()),
        directoryResolver,
        new DefaultClock());
  }

  public BuildTarget newBuildTarget(String fullyQualifiedName)
      throws IOException, InterruptedException {
    return BuildTargetFactory.newInstance(asCell().getFilesystem(), fullyQualifiedName);
  }

  /** The result of running {@code buck} from the command line. */
  public static class ProcessResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;

    private ProcessResult(
        int exitCode,
        String stdout,
        String stderr) {
      this.exitCode = exitCode;
      this.stdout = Preconditions.checkNotNull(stdout);
      this.stderr = Preconditions.checkNotNull(stderr);
    }

    /**
     * Returns the exit code from the process.
     * <p>
     * Currently, in practice, any time a client might want to use it, it is more appropriate to
     * use {@link #assertSuccess()} or {@link #assertFailure()} instead.
     */
    public int getExitCode() {
      return exitCode;
    }

    public String getStdout() {
      return stdout;
    }

    public String getStderr() {
      return stderr;
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
      return assertExitCode(null, TestRunning.TEST_FAILURES_EXIT_CODE);
    }

    public ProcessResult assertTestFailure(String message) {
      return assertExitCode(message, TestRunning.TEST_FAILURES_EXIT_CODE);
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

  public void assertFilesEqual(Path expected, Path actual) throws IOException {
    if (!expected.isAbsolute()) {
      expected = templatePath.resolve(expected);
    }
    if (!actual.isAbsolute()) {
      actual = destPath.resolve(actual);
    }
    if (!Files.isRegularFile(actual)) {
      fail("Expected file " + actual + " could not be found.");
    }

    String extension = MorePaths.getFileExtension(actual);
    String cleanPathToObservedFile = MoreStrings.withoutSuffix(
        templatePath.relativize(expected).toString(), EXPECTED_SUFFIX);

    switch (extension) {
      // For Apple .plist and .stringsdict files, we define equivalence if:
      // 1. The two files are the same type (XML or binary)
      // 2. If binary: unserialized objects are deeply-equivalent.
      //    Otherwise, fall back to exact string match.
      case "plist":
      case "stringsdict":
        NSObject expectedObject;
        try {
          expectedObject = BinaryPropertyListParser.parse(expected.toFile());
        } catch (Exception e) {
          // Not binary format.
          expectedObject = null;
        }

        NSObject observedObject;
        try {
          observedObject = BinaryPropertyListParser.parse(actual.toFile());
        } catch (Exception e) {
          // Not binary format.
          observedObject = null;
        }

        assertTrue(
            String.format(
                "In %s, expected plist to be of %s type.",
                cleanPathToObservedFile,
                (expectedObject != null) ? "binary" : "XML"),
            (expectedObject != null) == (observedObject != null));

        if (expectedObject != null) {
          // These keys depend on the locally installed version of Xcode, so ignore them
          // in comparisons.
          String[] ignoredKeys = {
              "DTSDKName",
              "DTPlatformName",
              "DTPlatformVersion",
              "MinimumOSVersion",
              "DTSDKBuild",
              "DTPlatformBuild",
              "DTXcode",
              "DTXcodeBuild"
          };
          if (observedObject instanceof NSDictionary &&
              expectedObject instanceof NSDictionary) {
            for (String key : ignoredKeys) {
              ((NSDictionary) observedObject).remove(key);
              ((NSDictionary) expectedObject).remove(key);
            }
          }

          assertEquals(
              String.format(
                  "In %s, expected binary plist contents to match.",
                  cleanPathToObservedFile),
              expectedObject,
              observedObject);
          break;
        } else {
          assertFileContentsEqual(expected, actual);
        }
        break;

      default:
        assertFileContentsEqual(expected, actual);
    }
  }

  private void assertFileContentsEqual(Path expectedFile, Path observedFile) throws IOException {
    String cleanPathToObservedFile = MoreStrings.withoutSuffix(
        templatePath.relativize(expectedFile).toString(), EXPECTED_SUFFIX);

    String expectedFileContent = new String(Files.readAllBytes(expectedFile), UTF_8);
    String observedFileContent = new String(Files.readAllBytes(observedFile), UTF_8);
    // It is possible, on Windows, to have Git keep "\n"-style newlines, or convert them to
    // "\r\n"-style newlines.  Support both ways by normalizing to "\n"-style newlines.
    // See https://help.github.com/articles/dealing-with-line-endings/ for more information.
    expectedFileContent = expectedFileContent.replace("\r\n", "\n");
    observedFileContent = observedFileContent.replace("\r\n", "\n");
    assertEquals(
        String.format(
            "In %s, expected content of %s to match that of %s.",
            cleanPathToObservedFile,
            expectedFileContent,
            observedFileContent),
        expectedFileContent,
        observedFileContent);
  }

  /**
   * For every file in the template directory whose name ends in {@code .expected}, checks that an
   * equivalent file has been written in the same place under the destination directory.
   *
   * @param templateSubdirectory An optional subdirectory to check. Only files in this directory
   *                     will be compared.
   */
  private void assertPathsEqual(final Path templateSubdirectory, final Path destinationSubdirectory)
      throws IOException {
    SimpleFileVisitor<Path> copyDirVisitor = new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        String fileName = file.getFileName().toString();
        if (fileName.endsWith(EXPECTED_SUFFIX)) {
          // Get File for the file that should be written, but without the ".expected" suffix.
          Path generatedFileWithSuffix =
              destinationSubdirectory.resolve(templateSubdirectory.relativize(file));
          Path directory = generatedFileWithSuffix.getParent();
          Path observedFile = directory.resolve(MorePaths.getNameWithoutExtension(file));
          assertFilesEqual(file, observedFile);
        }
        return FileVisitResult.CONTINUE;
      }
    };

    Files.walkFileTree(templateSubdirectory, copyDirVisitor);
  }

  public void verify(Path templateSubdirectory, Path destinationSubdirectory) throws IOException {
    assertPathsEqual(
        templatePath.resolve(templateSubdirectory),
        destPath.resolve(destinationSubdirectory));
  }

  public void verify() throws IOException {
    assertPathsEqual(templatePath, destPath);
  }

  public void verify(Path subdirectory) throws IOException {
    assertPathsEqual(templatePath.resolve(subdirectory), destPath.resolve(subdirectory));
  }
}
