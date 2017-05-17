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
import static org.junit.Assert.assertThat;
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
import com.facebook.buck.config.CellConfig;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.Configs;
import com.facebook.buck.io.BuckPaths;
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
import com.facebook.buck.rules.CellProvider;
import com.facebook.buck.rules.DefaultCellPathResolver;
import com.facebook.buck.rules.KnownBuildRuleTypesFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreStrings;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.WatchmanWatcher;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.CommandMode;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.trace.ChromeTraceParser;
import com.facebook.buck.util.trace.ChromeTraceParser.ChromeTraceEventMatcher;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.martiansoftware.nailgun.NGContext;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.FileAlreadyExistsException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;

/**
 * {@link ProjectWorkspace} is a directory that contains a Buck project, complete with build files.
 *
 * <p>When {@link #setUp()} is invoked, the project files are cloned from a directory of testdata
 * into a tmp directory according to the following rule:
 *
 * <ul>
 *   <li>Files with the {@code .fixture} extension will be copied and renamed without the extension.
 *   <li>Files with the {@code .expected} extension will not be copied.
 * </ul>
 *
 * After {@link #setUp()} is invoked, the test should invoke Buck in that directory. As this is an
 * integration test, we expect that files will be written as a result of invoking Buck.
 *
 * <p>After Buck has been run, invoke {@link #verify()} to verify that Buck wrote the correct files.
 * For each file in the testdata directory with the {@code .expected} extension, {@link #verify()}
 * will check that a file with the same relative path (but without the {@code .expected} extension)
 * exists in the tmp directory. If not, {@link org.junit.Assert#fail()} will be invoked.
 */
public class ProjectWorkspace {

  private static final String FIXTURE_SUFFIX = "fixture";
  private static final String EXPECTED_SUFFIX = "expected";

  private static final String PATH_TO_BUILD_LOG = "buck-out/bin/build.log";

  private static final Function<Path, Path> BUILD_FILE_RENAME =
      new Function<Path, Path>() {
        @Override
        @Nullable
        public Path apply(Path path) {
          String fileName = path.getFileName().toString();
          String extension = com.google.common.io.Files.getFileExtension(fileName);
          switch (extension) {
            case FIXTURE_SUFFIX:
              return path.getParent()
                  .resolve(com.google.common.io.Files.getNameWithoutExtension(fileName));
            case EXPECTED_SUFFIX:
              return null;
            default:
              return path;
          }
        }
      };

  private boolean isSetUp = false;
  private boolean manageLocalConfigs = false;
  private final Map<String, Map<String, String>> localConfigs = new HashMap<>();
  private final Path templatePath;
  private final Path destPath;
  @Nullable private ProjectFilesystemAndConfig projectFilesystemAndConfig;

  private static class ProjectFilesystemAndConfig {
    private final ProjectFilesystem projectFilesystem;
    private final Config config;

    private ProjectFilesystemAndConfig(ProjectFilesystem projectFilesystem, Config config) {
      this.projectFilesystem = projectFilesystem;
      this.config = config;
    }
  }

  @VisibleForTesting
  ProjectWorkspace(Path templateDir, final Path targetFolder) {
    this.templatePath = templateDir;
    this.destPath = targetFolder;
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
                    destPath.resolve(BuckConstant.getBuckOutputPath().resolve(".currentversion")),
                    ImmutableSet.<OpenOption>of(
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))))) {
      outputStream.write(BuckVersion.getVersion().getBytes(Charsets.UTF_8));
    } catch (FileAlreadyExistsException | NoSuchFileException e) {
      // If the current version file already exists we don't need to create it
      // If buck-out doesn't exist we don't need to stamp it
    }

    if (Platform.detect() == Platform.WINDOWS) {
      // Hack for symlinks on Windows.
      SimpleFileVisitor<Path> copyDirVisitor =
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                throws IOException {
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

    if (!Files.exists(getPath(".buckconfig.local"))) {
      manageLocalConfigs = true;

      // Enable the JUL build log.  This log is very verbose but rarely useful,
      // so it's disabled by default.
      addBuckConfigLocalOption("log", "jul_build_log", "true");

      // Disable the directory cache by default.  Tests that want to enable it can call
      // `enableDirCache` on this object.  Only do this if a .buckconfig.local file does not already
      // exist, however (we assume the test knows what it is doing at that point).
      addBuckConfigLocalOption("cache", "mode", "");

      // Limit the number of threads by default to prevent multiple integration tests running at the
      // same time from creating a quadratic number of threads. Tests can disable this using
      // `disableThreadLimitOverride`.
      addBuckConfigLocalOption("build", "threads", "2");
    }

    // We have to have .watchmanconfig on windows, otherwise we have problems with deleting stuff
    // from buck-out while watchman indexes/touches files.
    if (!Files.exists(getPath(".watchmanconfig"))) {
      writeContentsToPath("{\"ignore_dirs\":[\"buck-out\",\".buckd\"]}", ".watchmanconfig");
    }

    isSetUp = true;
    return this;
  }

  private Map<String, String> getBuckConfigLocalSection(String section) {
    Map<String, String> newValue = new HashMap<>();
    Map<String, String> oldValue = localConfigs.putIfAbsent(section, newValue);
    if (oldValue != null) {
      return oldValue;
    } else {
      return newValue;
    }
  }

  public void addBuckConfigLocalOption(String section, String key, String value)
      throws IOException {
    getBuckConfigLocalSection(section).put(key, value);
    saveBuckConfigLocal();
  }

  public void removeBuckConfigLocalOption(String section, String key) throws IOException {
    getBuckConfigLocalSection(section).remove(key);
    saveBuckConfigLocal();
  }

  private void saveBuckConfigLocal() throws IOException {
    Preconditions.checkArgument(
        manageLocalConfigs,
        "ProjectWorkspace cannot modify .buckconfig.local because "
            + "a custom one is already present in the test data directory");
    StringBuilder contents = new StringBuilder();
    for (Map.Entry<String, Map<String, String>> section : localConfigs.entrySet()) {
      contents.append("[").append(section.getKey()).append("]\n\n");
      for (Map.Entry<String, String> option : section.getValue().entrySet()) {
        contents.append(option.getKey()).append(" = ").append(option.getValue()).append("\n");
      }
      contents.append("\n");
    }
    writeContentsToPath(contents.toString(), ".buckconfig.local");
  }

  private ProjectFilesystemAndConfig getProjectFilesystemAndConfig()
      throws InterruptedException, IOException {
    if (projectFilesystemAndConfig == null) {
      Config config = Configs.createDefaultConfig(destPath);
      projectFilesystemAndConfig =
          new ProjectFilesystemAndConfig(new ProjectFilesystem(destPath, config), config);
    }
    return projectFilesystemAndConfig;
  }

  public BuckPaths getBuckPaths() throws InterruptedException, IOException {
    return getProjectFilesystemAndConfig().projectFilesystem.getBuckPaths();
  }

  public ProcessResult runBuckBuild(String... args) throws IOException {
    String[] totalArgs = new String[args.length + 1];
    totalArgs[0] = "build";
    System.arraycopy(args, 0, totalArgs, 1, args.length);
    return runBuckCommand(totalArgs);
  }

  public ProcessResult runBuckDistBuildRun(String... args) throws IOException {
    String[] totalArgs = new String[args.length + 2];
    totalArgs[0] = "distbuild";
    totalArgs[1] = "run";
    System.arraycopy(args, 0, totalArgs, 2, args.length);
    return runBuckCommand(totalArgs);
  }

  private ImmutableMap<String, String> buildMultipleAndReturnStringOutputs(String... args)
      throws IOException {
    // Add in `--show-output` to the build, so we can parse the output paths after the fact.
    ImmutableList<String> buildArgs =
        ImmutableList.<String>builder().add("--show-output").add(args).build();
    ProjectWorkspace.ProcessResult buildResult =
        runBuckBuild(buildArgs.toArray(new String[buildArgs.size()]));
    buildResult.assertSuccess();

    // Grab the stdout lines, which have the build outputs.
    List<String> lines =
        Splitter.on(CharMatcher.anyOf(System.lineSeparator()))
            .trimResults()
            .omitEmptyStrings()
            .splitToList(buildResult.getStdout());

    // Skip the first line, which is just "The outputs are:".
    assertThat(lines.get(0), Matchers.equalTo("The outputs are:"));
    lines = lines.subList(1, lines.size());

    Splitter lineSplitter = Splitter.on(' ').trimResults();
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    for (String line : lines) {
      List<String> fields = lineSplitter.splitToList(line);
      assertThat(fields, Matchers.hasSize(2));
      builder.put(fields.get(0), fields.get(1));
    }

    return builder.build();
  }

  public ImmutableMap<String, Path> buildMultipleAndReturnOutputs(String... args)
      throws IOException {
    return buildMultipleAndReturnStringOutputs(args)
        .entrySet()
        .stream()
        .collect(
            MoreCollectors.toImmutableMap(
                entry -> entry.getKey(), entry -> getPath(entry.getValue())));
  }

  public Path buildAndReturnOutput(String... args) throws IOException {
    ImmutableMap<String, Path> outputs = buildMultipleAndReturnOutputs(args);

    // Verify we only have a single output.
    assertThat(
        String.format(
            "expected only a single build target in command `%s`: %s",
            ImmutableList.copyOf(args), outputs),
        outputs.entrySet(),
        Matchers.hasSize(1));

    return outputs.values().iterator().next();
  }

  public ImmutableMap<String, Path> buildMultipleAndReturnRelativeOutputs(String... args)
      throws IOException {
    return buildMultipleAndReturnStringOutputs(args)
        .entrySet()
        .stream()
        .collect(
            MoreCollectors.toImmutableMap(
                entry -> entry.getKey(), entry -> Paths.get(entry.getValue())));
  }

  public Path buildAndReturnRelativeOutput(String... args) throws IOException {
    ImmutableMap<String, Path> outputs = buildMultipleAndReturnRelativeOutputs(args);

    // Verify we only have a single output.
    assertThat(
        String.format(
            "expected only a single build target in command `%s`: %s",
            ImmutableList.copyOf(args), outputs),
        outputs.entrySet(),
        Matchers.hasSize(1));

    return outputs.values().iterator().next();
  }

  public ProcessExecutor.Result runJar(Path jar, ImmutableList<String> vmArgs, String... args)
      throws IOException, InterruptedException {
    List<String> command =
        ImmutableList.<String>builder()
            .add(
                JavaCompilationConstants.DEFAULT_JAVA_OPTIONS.getJavaRuntimeLauncher().getCommand())
            .addAll(vmArgs)
            .add("-jar")
            .add(jar.toString())
            .addAll(ImmutableList.copyOf(args))
            .build();
    return doRunCommand(command);
  }

  public ProcessExecutor.Result runJar(Path jar, String... args)
      throws IOException, InterruptedException {
    return runJar(jar, ImmutableList.of(), args);
  }

  public ProcessExecutor.Result runCommand(String exe, String... args)
      throws IOException, InterruptedException {
    List<String> command =
        ImmutableList.<String>builder().add(exe).addAll(ImmutableList.copyOf(args)).build();
    return doRunCommand(command);
  }

  private ProcessExecutor.Result doRunCommand(List<String> command)
      throws IOException, InterruptedException {
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(command)
            .setDirectory(destPath.toAbsolutePath())
            .build();
    ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());
    return executor.launchAndExecute(params);
  }

  /**
   * Runs Buck with the specified list of command-line arguments.
   *
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *     {@code ["project"]}, etc.
   * @return the result of running Buck, which includes the exit code, stdout, and stderr.
   */
  public ProcessResult runBuckCommand(String... args) throws IOException {
    return runBuckCommandWithEnvironmentOverridesAndContext(
        destPath, Optional.empty(), ImmutableMap.of(), args);
  }

  public ProcessResult runBuckCommand(ImmutableMap<String, String> environment, String... args)
      throws IOException {
    return runBuckCommandWithEnvironmentOverridesAndContext(
        destPath, Optional.empty(), environment, args);
  }

  public ProcessResult runBuckCommand(Path repoRoot, String... args) throws IOException {
    return runBuckCommandWithEnvironmentOverridesAndContext(
        repoRoot, Optional.empty(), ImmutableMap.of(), args);
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
      NGContext context, CapturingPrintStream stderr, String... args) throws IOException {
    assumeTrue(
        "watchman must exist to run buckd",
        new ExecutableFinder(Platform.detect())
            .getOptionalExecutable(Paths.get("watchman"), ImmutableMap.copyOf(System.getenv()))
            .isPresent());
    return runBuckCommandWithEnvironmentOverridesAndContext(
        destPath, Optional.of(context), ImmutableMap.of(), stderr, args);
  }

  public ProcessResult runBuckCommandWithEnvironmentOverridesAndContext(
      Path repoRoot,
      Optional<NGContext> context,
      ImmutableMap<String, String> environmentOverrides,
      String... args)
      throws IOException {
    return runBuckCommandWithEnvironmentOverridesAndContext(
        repoRoot, context, environmentOverrides, new CapturingPrintStream(), args);
  }

  public ProcessResult runBuckCommandWithEnvironmentOverridesAndContext(
      Path repoRoot,
      Optional<NGContext> context,
      ImmutableMap<String, String> environmentOverrides,
      CapturingPrintStream stderr,
      String... args)
      throws IOException {
    assertTrue("setUp() must be run before this method is invoked", isSetUp);
    CapturingPrintStream stdout = new CapturingPrintStream();
    InputStream stdin = new ByteArrayInputStream("".getBytes());

    // Construct a limited view of the parent environment for the child.
    // TODO(#5754812): we should eventually get tests working without requiring these be set.
    ImmutableList<String> inheritedEnvVars =
        ImmutableList.of(
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
    Map<String, String> envBuilder = new HashMap<>();
    for (String variable : inheritedEnvVars) {
      String value = System.getenv(variable);
      if (value != null) {
        envBuilder.put(variable, value);
      }
    }
    envBuilder.putAll(environmentOverrides);
    ImmutableMap<String, String> sanizitedEnv = ImmutableMap.copyOf(envBuilder);

    Main main = new Main(stdout, stderr, stdin);
    int exitCode;
    try {
      exitCode =
          main.runMainWithExitCode(
              new BuildId(),
              repoRoot,
              context,
              sanizitedEnv,
              CommandMode.TEST,
              WatchmanWatcher.FreshInstanceAction.NONE,
              System.nanoTime(),
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

  /**
   * Runs an event-driven parser on {@code buck-out/log/build.trace}, which is a symlink to the
   * trace of the most recent invocation of Buck (which may not have been a {@code buck build}).
   *
   * @see ChromeTraceParser#parse(Path, Set)
   */
  public Map<ChromeTraceEventMatcher<?>, Object> parseTraceFromMostRecentBuckInvocation(
      Set<ChromeTraceEventMatcher<?>> matchers) throws InterruptedException, IOException {
    ProjectFilesystem projectFilesystem = getProjectFilesystemAndConfig().projectFilesystem;
    ChromeTraceParser parser = new ChromeTraceParser(projectFilesystem);
    return parser.parse(
        projectFilesystem.getBuckPaths().getLogDir().resolve("build.trace"), matchers);
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
      case FREEBSD:
        platformExt = "freebsd";
        break;
      case UNKNOWN:
        // Leave platformExt as null.
        break;
    }
    if (platformExt != null) {
      String extension = com.google.common.io.Files.getFileExtension(path.toString());
      String basename = com.google.common.io.Files.getNameWithoutExtension(path.toString());
      Path platformPath =
          extension.length() > 0
              ? path.getParent()
                  .resolve(String.format("%s.%s.%s", basename, platformExt, extension))
              : path.getParent().resolve(String.format("%s.%s", basename, platformExt));
      if (platformPath.toFile().exists()) {
        path = platformPath;
      }
    }
    return new String(Files.readAllBytes(path), UTF_8);
  }

  public void enableDirCache() throws IOException {
    addBuckConfigLocalOption("cache", "mode", "dir");
  }

  public void setupCxxSandboxing(boolean sandboxSources) throws IOException {
    addBuckConfigLocalOption("cxx", "sandbox_sources", Boolean.toString(sandboxSources));
  }

  public void disableThreadLimitOverride() throws IOException {
    removeBuckConfigLocalOption("build", "threads");
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

  public void replaceFileContents(
      String pathRelativeToProjectRoot, String target, String replacement) throws IOException {
    String fileContents = getFileContents(pathRelativeToProjectRoot);
    fileContents = fileContents.replace(target, replacement);
    writeContentsToPath(fileContents, pathRelativeToProjectRoot);
  }

  public void writeContentsToPath(
      String contents, String pathRelativeToProjectRoot, OpenOption... options) throws IOException {
    Files.write(getPath(pathRelativeToProjectRoot), contents.getBytes(UTF_8), options);
  }

  /** @return the specified path resolved against the root of this workspace. */
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
        getDestPath(), Files.readAllLines(getPath(PATH_TO_BUILD_LOG), UTF_8));
  }

  public Cell asCell() throws IOException, InterruptedException {
    ProjectFilesystemAndConfig filesystemAndConfig = getProjectFilesystemAndConfig();
    ProjectFilesystem filesystem = filesystemAndConfig.projectFilesystem;
    Config config = filesystemAndConfig.config;

    TestConsole console = new TestConsole();
    ImmutableMap<String, String> env = ImmutableMap.copyOf(System.getenv());
    DefaultAndroidDirectoryResolver directoryResolver =
        new DefaultAndroidDirectoryResolver(
            filesystem.getRootPath().getFileSystem(), env, Optional.empty(), Optional.empty());
    return CellProvider.createForLocalBuild(
            filesystem,
            Watchman.NULL_WATCHMAN,
            new BuckConfig(
                config,
                filesystem,
                Architecture.detect(),
                Platform.detect(),
                env,
                new DefaultCellPathResolver(filesystem.getRootPath(), config)),
            CellConfig.of(),
            new KnownBuildRuleTypesFactory(new DefaultProcessExecutor(console), directoryResolver))
        .getCellByPath(filesystem.getRootPath());
  }

  public BuildTarget newBuildTarget(String fullyQualifiedName)
      throws IOException, InterruptedException {
    return BuildTargetFactory.newInstance(
        asCell().getFilesystem().getRootPath(), fullyQualifiedName);
  }

  /** The result of running {@code buck} from the command line. */
  public static class ProcessResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;

    private ProcessResult(int exitCode, String stdout, String stderr) {
      this.exitCode = exitCode;
      this.stdout = Preconditions.checkNotNull(stdout);
      this.stderr = Preconditions.checkNotNull(stderr);
    }

    /**
     * Returns the exit code from the process.
     *
     * <p>Currently, in practice, any time a client might want to use it, it is more appropriate to
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

    public ProcessResult assertExitCode(@Nullable String message, int exitCode) {
      if (exitCode == getExitCode()) {
        return this;
      }

      String failureMessage =
          String.format("Expected exit code %d but was %d.", exitCode, getExitCode());
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
    String cleanPathToObservedFile =
        MoreStrings.withoutSuffix(templatePath.relativize(expected).toString(), EXPECTED_SUFFIX);

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
                cleanPathToObservedFile, (expectedObject != null) ? "binary" : "XML"),
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
          if (observedObject instanceof NSDictionary && expectedObject instanceof NSDictionary) {
            for (String key : ignoredKeys) {
              ((NSDictionary) observedObject).remove(key);
              ((NSDictionary) expectedObject).remove(key);
            }
          }

          assertEquals(
              String.format(
                  "In %s, expected binary plist contents to match.", cleanPathToObservedFile),
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
    String cleanPathToObservedFile =
        MoreStrings.withoutSuffix(
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
            cleanPathToObservedFile, expectedFileContent, observedFileContent),
        expectedFileContent,
        observedFileContent);
  }

  /**
   * For every file in the template directory whose name ends in {@code .expected}, checks that an
   * equivalent file has been written in the same place under the destination directory.
   *
   * @param templateSubdirectory An optional subdirectory to check. Only files in this directory
   *     will be compared.
   */
  private void assertPathsEqual(final Path templateSubdirectory, final Path destinationSubdirectory)
      throws IOException {
    SimpleFileVisitor<Path> copyDirVisitor =
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
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
        templatePath.resolve(templateSubdirectory), destPath.resolve(destinationSubdirectory));
  }

  public void verify() throws IOException {
    assertPathsEqual(templatePath, destPath);
  }

  public void verify(Path subdirectory) throws IOException {
    Preconditions.checkArgument(
        !subdirectory.isAbsolute(),
        "'verify(subdirectory)' takes a relative path, but received '%s'",
        subdirectory);
    assertPathsEqual(templatePath.resolve(subdirectory), destPath.resolve(subdirectory));
  }
}
