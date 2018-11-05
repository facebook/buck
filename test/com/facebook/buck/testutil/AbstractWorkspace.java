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

package com.facebook.buck.testutil;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.rules.keys.config.impl.BuckVersion;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * {@link AbstractWorkspace} is a directory that contains a Buck project, complete with build files.
 * It requires that its implementations provide a way of running a buckCommand.
 *
 * <p>It provides ways of adding testdata to the Buck projects, complete with BuildFiles, and ways
 * of overriding the copied .buckconfig by adding configs to a BuckConfigLocal.
 */
public abstract class AbstractWorkspace {

  /**
   * Describes at what fixtureLevel a file was in when it was saved. Needed since when we save
   * fixtured files, they will no longer have those fixtures attached
   */
  private enum FixtureLevel {
    NOT_A_FIXTURE(0),
    FIXTURE(1),
    TEST_CLASS_FIXTURE(2),
    TEST_CASE_FIXTURE(3);

    private final int level;

    FixtureLevel(int level) {
      this.level = level;
    }

    /** @return integer value of the fixture level */
    @JsonValue
    public int getLevel() {
      return level;
    }
  }

  protected static final String FIXTURE_SUFFIX = "fixture";
  protected static final String EXPECTED_SUFFIX = "expected";
  protected static final String SKIP_SUFFIX = "win.expected";

  protected Path destPath;
  private final Map<String, Map<String, String>> localConfigs = new HashMap<>();
  private boolean firstTemplateAdded = false;

  private Optional<String> testClassFixtureSuffix = Optional.empty();
  private Optional<String> testCaseFixtureSuffix = Optional.empty();
  private Map<Path, FixtureLevel> writtenFileFixtureLevel = new HashMap<>();

  /**
   * Constructor for AbstractWorkspace
   *
   * @param destPath is the folder where the project will be stored. It is generally a temporary
   *     folder
   */
  protected AbstractWorkspace(Path destPath) {
    this.destPath = destPath;
  }

  /**
   * Constructor for AbstractWorkspace. Please note that if you use this constructor, you MUST set
   * destPath before using methods that rely on it.
   */
  protected AbstractWorkspace() {}

  /**
   * After attaching testClassName and testName as fixture suffixes, the files with those suffixes
   * will replace any with just the fixture suffix.
   *
   * <p>Example: BUCK.fixture < BUCK.fixtureTestClass < BUCK.fixtureTestClassTestCase
   */
  public void attachTestSpecificFixtureSuffixes(String testClassName, String testName) {
    testClassFixtureSuffix = Optional.of(FIXTURE_SUFFIX + testClassName);
    testCaseFixtureSuffix = Optional.of(testClassFixtureSuffix.get() + testName);
  }

  private Map<String, String> getBuckConfigLocalSection(String section) {
    Map<String, String> newValue = new HashMap<>();
    Map<String, String> oldValue = localConfigs.putIfAbsent(section, newValue);
    if (oldValue != null) {
      return oldValue;
    }
    return newValue;
  }

  private void saveBuckConfigLocal() throws IOException {
    writeContentsToPath(convertToBuckConfig(localConfigs), ".buckconfig.local");
  }

  protected static String convertToBuckConfig(Map<String, Map<String, String>> configs) {
    StringBuilder contents = new StringBuilder();
    for (Map.Entry<String, Map<String, String>> section : configs.entrySet()) {
      contents.append("[").append(section.getKey()).append("]\n\n");
      for (Map.Entry<String, String> option : section.getValue().entrySet()) {
        contents.append(option.getKey()).append(" = ").append(option.getValue()).append("\n");
      }
      contents.append("\n");
    }
    return contents.toString();
  }

  /**
   * Overrides buckconfig options with the given value at the given section's option.
   *
   * @param section is the section name where option is defined
   * @param key is the option name
   * @param value is the new value for the option
   * @throws IOException when saving the new BuckConfigLocal has an issue
   */
  public void addBuckConfigLocalOption(String section, String key, String value)
      throws IOException {
    getBuckConfigLocalSection(section).put(key, value);
    saveBuckConfigLocal();
  }

  /**
   * Overrides buckconfig options with the given values in a map of {section: {key: value}}
   *
   * @throws IOException when saving the new BuckConfigLocal has an issue
   */
  public void addBuckConfigLocalOptions(Map<String, Map<String, String>> localConfigs)
      throws IOException {
    if (localConfigs.isEmpty()) {
      // avoid saving if there's nothing in the map
      return;
    }
    for (Map.Entry<String, Map<String, String>> section : localConfigs.entrySet()) {
      for (Map.Entry<String, String> option : section.getValue().entrySet()) {
        getBuckConfigLocalSection(section.getKey()).put(option.getKey(), option.getValue());
      }
    }
    saveBuckConfigLocal();
  }

  /**
   * Removes overriding buckconfig options at the given section's option. If the option is not being
   * overridden, then nothing happens.
   *
   * @param section is the section name where option is defined
   * @param key is the option name
   * @throws IOException when saving the new BuckConfigLocal has an issue
   */
  public void removeBuckConfigLocalOption(String section, String key) throws IOException {
    getBuckConfigLocalSection(section).remove(key);
    saveBuckConfigLocal();
  }

  /** Stamp the buck-out directory if it exists and isn't stamped already */
  private void stampBuckVersion() throws IOException {
    if (!Files.exists(destPath.resolve(BuckConstant.getBuckOutputPath()))) {
      return;
    }
    try (OutputStream outputStream =
        new BufferedOutputStream(
            Channels.newOutputStream(
                Files.newByteChannel(
                    destPath.resolve(BuckConstant.getBuckOutputPath().resolve(".currentversion")),
                    ImmutableSet.<OpenOption>of(
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE))))) {
      outputStream.write(BuckVersion.getVersion().getBytes(Charsets.UTF_8));
    }
  }

  private void ensureNoLocalBuckConfig(Path templatePath) {
    if (Files.exists(templatePath.resolve(".buckconfig.local"))) {
      throw new IllegalStateException(
          "Found a .buckconfig.local in the Workspace template, which is illegal."
              + "  Use addBuckConfigLocalOption instead.");
    }
  }

  private void addDefaultLocalBuckConfigs() throws IOException {
    // Disable the directory cache by default.  Tests that want to enable it can call
    // `enableDirCache` on this object.  Only do this if a .buckconfig.local file does not already
    // exist, however (we assume the test knows what it is doing at that point).
    addBuckConfigLocalOption("cache", "mode", "");

    // Limit the number of threads by default to prevent multiple integration tests running at the
    // same time from creating a quadratic number of threads. Tests can disable this using
    // `disableThreadLimitOverride`.
    addBuckConfigLocalOption("build", "threads", "2");
  }

  private void ensureWatchmanConfig() throws IOException {
    // We have to have .watchmanconfig on windows, otherwise we have problems with deleting stuff
    // from buck-out while watchman indexes/touches files.
    if (!Files.exists(getPath(".watchmanconfig"))) {
      writeContentsToPath("{\"ignore_dirs\":[\"buck-out\",\".buckd\"]}", ".watchmanconfig");
    }
  }

  private FileSystem getOrCreateJarFileSystem(URI jarURI) throws IOException {
    try {
      return FileSystems.getFileSystem(jarURI);
    } catch (FileSystemNotFoundException e) {
      Map<String, String> env = new HashMap<>();
      env.put("create", "true");
      return FileSystems.newFileSystem(jarURI, env);
    }
  }

  private FixtureLevel extensionIsFixture(String extension) {
    if (extension.equals(FIXTURE_SUFFIX)) {
      return FixtureLevel.FIXTURE;
    }
    if (testClassFixtureSuffix.isPresent() && extension.equals(testClassFixtureSuffix.get())) {
      return FixtureLevel.TEST_CLASS_FIXTURE;
    }
    if (testCaseFixtureSuffix.isPresent() && extension.equals(testCaseFixtureSuffix.get())) {
      return FixtureLevel.TEST_CASE_FIXTURE;
    }
    return FixtureLevel.NOT_A_FIXTURE;
  }

  private Optional<Path> copyFilePath(Path contentPath) {
    String fileName = contentPath.getFileName().toString();
    String extension = com.google.common.io.Files.getFileExtension(fileName);
    if (extension.equals(EXPECTED_SUFFIX)) {
      return Optional.empty();
    }

    FixtureLevel fixtureLevel = extensionIsFixture(extension);
    if (fixtureLevel == FixtureLevel.NOT_A_FIXTURE) {
      return Optional.of(contentPath);
    }

    Path outputPath =
        contentPath
            .getParent()
            .resolve(com.google.common.io.Files.getNameWithoutExtension(fileName));
    // Only copy fixture if it has not yet been copied, or if only less specific fixtures have
    // already been written
    if (fixtureLevel.getLevel()
        < writtenFileFixtureLevel.getOrDefault(outputPath, FixtureLevel.NOT_A_FIXTURE).getLevel()) {
      return Optional.empty();
    }
    writtenFileFixtureLevel.put(outputPath, fixtureLevel);
    return Optional.of(outputPath);
  }

  private void copyTemplateContentsToDestPath(
      FileSystemProvider provider, Path templatePath, Path contentPath) throws IOException {
    Optional<Path> optionalOutputPath = copyFilePath(contentPath);
    if (!optionalOutputPath.isPresent()) {
      return;
    }
    Path outputPath = templatePath.relativize(optionalOutputPath.get());
    Path targetPath = destPath.resolve(outputPath.toString());

    try (InputStream inStream = provider.newInputStream(contentPath);
        FileOutputStream outStream = new FileOutputStream(targetPath.toString())) {
      byte[] buffer = new byte[inStream.available()];
      inStream.read(buffer);
      outStream.write(buffer);
    }
    if (Platform.detect() == Platform.WINDOWS) {
      return;
    }
    // require that certain files are executable.
    // the jar process removes any granularity around this, so we give everything the permission
    Set<PosixFilePermission> targetPermissions = Files.getPosixFilePermissions(targetPath);
    targetPermissions.add(PosixFilePermission.OWNER_EXECUTE);
    targetPermissions.add(PosixFilePermission.GROUP_EXECUTE);
    targetPermissions.add(PosixFilePermission.OTHERS_EXECUTE);
    Files.setPosixFilePermissions(targetPath, targetPermissions);
  }

  private void preAddTemplateActions(Path templatePath) throws IOException {
    ensureNoLocalBuckConfig(templatePath);
  }

  private void postAddTemplateActions() throws IOException {
    if (!firstTemplateAdded) {
      firstTemplateAdded = true;
      stampBuckVersion();
      addDefaultLocalBuckConfigs();
      ensureWatchmanConfig();
    }
  }

  /**
   * This will copy the template directory, renaming files named {@code foo.fixture} to {@code foo}
   * in the process. Files whose names end in {@code .expected} will not be copied.
   */
  public void addTemplateToWorkspace(Path templatePath) throws IOException {
    preAddTemplateActions(templatePath);
    // renames those with FIXTURE_SUFFIX, removes those with EXPECTED_SUFFIX
    MostFiles.copyRecursively(
        templatePath, destPath, (Path path) -> copyFilePath(path).orElse(null));

    if (Platform.detect() == Platform.WINDOWS) {
      // Hack for symlinks on Windows.
      SimpleFileVisitor<Path> copyDirVisitor =
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                throws IOException {
              // On Windows, symbolic links from git repository are checked out as normal files
              // containing a one-line path. In order to distinguish them, paths are read and
              // pointed
              // files are trued to locate. Once the pointed file is found, it will be copied to
              // target.
              // On NTFS length of path must be greater than 0 and less than 4096.
              if (attrs.size() > 0 && attrs.size() <= 4096) {
                String linkTo = new String(Files.readAllBytes(path), UTF_8);
                Path linkToFile;
                try {
                  linkToFile = templatePath.resolve(linkTo);
                } catch (InvalidPathException e) {
                  // Let's assume we were reading a normal text file, and not something meant to be
                  // a
                  // link.
                  return FileVisitResult.CONTINUE;
                }
                if (Files.isRegularFile(linkToFile)) {
                  Files.copy(linkToFile, path, StandardCopyOption.REPLACE_EXISTING);
                } else if (Files.isDirectory(linkToFile)) {
                  Files.delete(path);
                  MostFiles.copyRecursively(linkToFile, path);
                }
              }
              return FileVisitResult.CONTINUE;
            }
          };
      Files.walkFileTree(destPath, copyDirVisitor);
    }

    postAddTemplateActions();
  }

  /**
   * Copies a template to the Workspace regardless of what provider the template has its path
   * defined with. This is needed since using the "regular" copying mechanics with our destPath and
   * a non-default FileSystemProvider causes a ProviderMismatchException
   */
  private void copyTemplateToWorkspace(FileSystemProvider provider, Path templatePath)
      throws IOException {
    Queue<Path> contentQueue = new ArrayDeque<>();
    addDirectoryContentToQueue(provider, templatePath, contentQueue);
    while (!contentQueue.isEmpty()) {
      Path contentPath = contentQueue.remove();
      if (Files.isDirectory(contentPath)) {
        Files.createDirectory(destPath.resolve(templatePath.relativize(contentPath).toString()));
        addDirectoryContentToQueue(provider, contentPath, contentQueue);
      } else {
        copyTemplateContentsToDestPath(provider, templatePath, contentPath);
      }
    }
  }

  /**
   * This will copy the template directory, renaming files named {@code foo.fixture} to {@code foo}
   * in the process. Files whose names end in {@code .expected} will not be copied.
   *
   * <p>Assumes that {@param testDataResource} is contained in a jar.
   */
  public void addTemplateToWorkspace(URL testDataResource, String templateName) throws Exception {
    // not using "!/" as the ZipFileProvider (which is what is used for Jar files) treats "/" as the
    // root of the jar, and we want to keep it in the split
    String[] jarSplit = testDataResource.toURI().toString().split("!");
    URI jarURI = URI.create(jarSplit[0]);
    FileSystem testDataFS = getOrCreateJarFileSystem(jarURI);
    FileSystemProvider provider = testDataFS.provider();
    Path templatePath = testDataFS.getPath(jarSplit[1], templateName);
    preAddTemplateActions(templatePath);

    copyTemplateToWorkspace(provider, templatePath);
    postAddTemplateActions();
  }

  private void addDirectoryContentToQueue(
      FileSystemProvider provider, Path dirPath, Queue<Path> contentQueue) throws IOException {
    try (DirectoryStream<Path> dirStream = provider.newDirectoryStream(dirPath, entry -> true)) {
      for (Path contents : dirStream) {
        contentQueue.add(contents);
      }
    }
  }

  /**
   * Runs Buck with the specified list of command-line arguments.
   *
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *     {@code ["project"]}, etc.
   * @return the result of running Buck, which includes the exit code, stdout, and stderr.
   */
  public abstract ProcessResult runBuckCommand(String... args) throws Exception;

  /**
   * Runs Buck with the specified list of command-line arguments with the given map of environment
   * variables.
   *
   * @param environment set of environment variables to override
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *     {@code ["project"]}, etc.
   * @return the result of running Buck, which includes the exit code, stdout, and stderr.
   */
  public abstract ProcessResult runBuckCommand(
      ImmutableMap<String, String> environment, String... args) throws Exception;

  /**
   * Gets the path the workspace is held.
   *
   * @return the path the workspace is held
   */
  public Path getDestPath() {
    return destPath;
  }

  /**
   * Resolves the given path relative to the path the workspace is held.
   *
   * @param pathRelativeToWorkspaceRoot is the path relative to the workspace's destPath
   * @return absolute path of the given path relative to the workspace's destPath
   */
  public Path getPath(Path pathRelativeToWorkspaceRoot) {
    return destPath.resolve(pathRelativeToWorkspaceRoot);
  }

  /**
   * Resolves the given path relative to the path the workspace is held.
   *
   * @param pathRelativeToWorkspaceRoot is the path relative to the workspace's destPath
   * @return absolute path of the given path relative to the workspace's destPath
   */
  public Path getPath(String pathRelativeToWorkspaceRoot) {
    return destPath.resolve(pathRelativeToWorkspaceRoot);
  }

  /**
   * Resolves the given path relative to the path the workspace is held and returns its contents
   *
   * @param pathRelativeToWorkspaceRoot is the path relative to the workspace's destPath
   * @return file contents at absolute path of the given path relative to the workspace's destPath
   */
  public String getFileContents(Path pathRelativeToWorkspaceRoot) throws IOException {
    return getFileContentsWithAbsolutePath(getPath(pathRelativeToWorkspaceRoot));
  }

  /**
   * Resolves the given path relative to the path the workspace is held and returns its contents
   *
   * @param pathRelativeToWorkspaceRoot is the path relative to the workspace's destPath
   * @return file contents at absolute path of the given path relative to the workspace's destPath
   */
  public String getFileContents(String pathRelativeToWorkspaceRoot) throws IOException {
    return getFileContentsWithAbsolutePath(getPath(pathRelativeToWorkspaceRoot));
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

  /**
   * Copies the file at source (relative to workspace root) to dest (relative to workspace root)
   *
   * @param source source path of file relative to workspace root
   * @param dest destination path of file relative to workspace root
   * @throws IOException
   */
  public void copyFile(String source, String dest) throws IOException {
    Path destination = getPath(dest);
    Files.deleteIfExists(destination);
    Files.copy(getPath(source), destination);
  }

  /**
   * Copies the files at source (absolute) to pathRelativeToWorkspaceRoot
   *
   * @param source source path of file relative to workspace root
   * @param pathRelativeToWorkspaceRoot destination directory of files relative to workspace root
   * @throws IOException
   */
  public void copyRecursively(Path source, Path pathRelativeToWorkspaceRoot) throws IOException {
    MostFiles.copyRecursively(source, destPath.resolve(pathRelativeToWorkspaceRoot));
  }

  /**
   * Moves the file at source (relative to workspace root) to dest (relative to workspace root)
   *
   * @param source source path of file relative to workspace root
   * @param dest destination path of file relative to workspace root
   * @throws IOException
   */
  public void move(String source, String dest) throws IOException {
    Files.move(getPath(source), getPath(dest));
  }

  /**
   * Replaces all instances of target with replacement at the given path
   *
   * @param pathRelativeToWorkspaceRoot path of file to replace contents of
   * @param target string to replace
   * @param replacement string to replace with
   * @return True if any contents of the file were replaced, False otherwise
   * @throws IOException
   */
  public boolean replaceFileContents(
      String pathRelativeToWorkspaceRoot, String target, String replacement) throws IOException {
    String fileContents = getFileContents(pathRelativeToWorkspaceRoot);
    String newFileContents = fileContents.replace(target, replacement);
    writeContentsToPath(newFileContents, pathRelativeToWorkspaceRoot);
    return !newFileContents.equals(fileContents);
  }

  /**
   * Create file (or overwrite existing file) with given contents at
   *
   * @param contents contents to write to the file
   * @param pathRelativeToWorkspaceRoot destination path of file relative to workspace root
   * @param options options (the same ones that Files.write takes)
   * @throws IOException
   */
  public void writeContentsToPath(
      String contents, String pathRelativeToWorkspaceRoot, OpenOption... options)
      throws IOException {
    Files.write(getPath(pathRelativeToWorkspaceRoot), contents.getBytes(UTF_8), options);
  }

  /** @return the specified path resolved against the root of this workspace. */
  public Path resolve(Path pathRelativeToWorkspaceRoot) {
    return destPath.resolve(pathRelativeToWorkspaceRoot);
  }

  /** @return the specified path resolved against the root of this workspace. */
  public Path resolve(String pathRelativeToWorkspaceRoot) {
    return destPath.resolve(pathRelativeToWorkspaceRoot);
  }
}
