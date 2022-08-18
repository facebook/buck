/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

/**
 * *************
 *
 * <p>This code can be embedded in arbitrary third-party projects! For maximum compatibility, use
 * only Java 7 constructs.
 *
 * <p>*************
 */
package com.facebook.buck.jvm.java;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class FatJarMain {

  public static final String FAT_JAR_INNER_JAR = "inner.jar";
  public static final String FAT_JAR_NATIVE_LIBRARIES_DIR = "nativelibs";
  public static final String WRAPPER_SCRIPT_MARKER_FILE = "wrapper_script";

  private static final String DEBUG_OPTION = "--debug";

  private FatJarMain() {}

  /**
   * Main method for fat jar. Unpacks artifact and native libraries into a temporary folder and then
   * launches original artifact as java process.
   */
  @SuppressWarnings("PMD.BlacklistedDefaultProcessMethod")
  public static void main(String[] args) throws Exception {

    boolean debug = args.length > 0 && args[0].equals(DEBUG_OPTION);

    ProcessBuilder processBuilder = new ProcessBuilder();
    Map<String, String> environment = processBuilder.environment();

    if (debug) {
      printDebugInfo("System envs: " + environment);
    }

    ClassLoader classLoader = FatJarMain.class.getClassLoader();

    // Create a temp dir to house the native libraries.
    try (ManagedTemporaryDirectory temp = new ManagedTemporaryDirectory("fatjar.", !debug)) {
      Path workingDirectory = temp.getPath();
      if (debug) {
        printDebugInfo("Created a temporary directory: " + workingDirectory);
      }

      // Unpack the real, inner artifact (JAR or wrapper script).
      boolean isWrapperScript = isWrapperScript();
      Path innerArtifact = workingDirectory.resolve(isWrapperScript ? "wrapper.sh" : "main.jar");
      if (debug) {
        printDebugInfo("Extracting inner artifact into: " + innerArtifact);
      }
      unpackInnerArtifactTo(classLoader, innerArtifact);
      if (isWrapperScript) {
        makeExecutable(innerArtifact);
      }

      // Unpack all the native libraries, since the system loader will need to find these on disk.
      Path nativeLibs = workingDirectory.resolve("native_libs");
      Files.createDirectory(nativeLibs);
      unpackNativeLibrariesInto(nativeLibs, debug);

      // Update the appropriate environment variable with the location of our native libraries
      // and start the real main class in a new process so that it picks it up.
      List<String> command =
          getCommand(
              isWrapperScript,
              innerArtifact,
              debug,
              debug ? Arrays.copyOfRange(args, 1, args.length) : args);
      if (debug) {
        printDebugInfo("Executing command: " + command);
      }
      processBuilder.command(command);
      updateEnvironment(environment, nativeLibs, debug);
      processBuilder.inheritIO();

      // Wait for the inner process to finish, and propagate it's exit code, before cleaning
      // up the native libraries.
      System.exit(processBuilder.start().waitFor());
    }
  }

  private static boolean isWrapperScript() throws IOException {
    try (JarFile jar = new JarFile(getJarPath())) {
      JarEntry entry = jar.getJarEntry(WRAPPER_SCRIPT_MARKER_FILE);
      return entry != null;
    }
  }

  private static void unpackNativeLibrariesInto(Path destination, boolean debug)
      throws IOException {
    try (JarFile jar = new JarFile(getJarPath())) {
      Enumeration<JarEntry> enumEntries = jar.entries();
      while (enumEntries.hasMoreElements()) {
        JarEntry jarEntry = enumEntries.nextElement();
        String entryName = jarEntry.getName();
        if (jarEntry.isDirectory() || !entryName.startsWith(FAT_JAR_NATIVE_LIBRARIES_DIR)) {
          continue;
        }

        String fileName = entryName.substring(FAT_JAR_NATIVE_LIBRARIES_DIR.length() + 1);
        if (debug) {
          printDebugInfo("Extracting native library: " + fileName);
        }
        Files.copy(jar.getInputStream(jarEntry), destination.resolve(fileName));
      }
    }
  }

  private static String getJarPath() throws IOException {
    ProtectionDomain protectionDomain = FatJarMain.class.getProtectionDomain();
    CodeSource codeSource = protectionDomain.getCodeSource();
    URL jarUrl = new URL("jar:" + codeSource.getLocation().toExternalForm() + "!/");
    URL jarFileUrl = ((JarURLConnection) jarUrl.openConnection()).getJarFileURL();
    return jarFileUrl.getPath();
  }

  private static void unpackInnerArtifactTo(ClassLoader loader, Path destination)
      throws IOException {
    try (InputStream input = loader.getResourceAsStream(FAT_JAR_INNER_JAR);
        BufferedInputStream bufferedInput =
            new BufferedInputStream(Objects.requireNonNull(input))) {
      Files.copy(bufferedInput, destination);
    }
  }

  /**
   * Update the library search path environment variable with the given native library directory.
   */
  private static void updateEnvironment(Map<String, String> env, Path libDir, boolean debug) {
    String librarySearchPathName = getLibrarySearchPathName();
    String originalLibPath = getEnvValue(librarySearchPathName);
    String newLibPath =
        libDir + (originalLibPath == null ? "" : File.pathSeparator + originalLibPath);

    if (debug) {
      printDebugInfo(
          String.format(
              "Setting/Updating env variable: '%s' with '%s' value",
              librarySearchPathName, newLibPath));
    }
    env.put(librarySearchPathName, newLibPath);
  }

  private static List<String> getJVMArguments() {
    RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
    try {
      return runtimeMxBean.getInputArguments();
    } catch (java.lang.SecurityException e) {
      // Do not have the ManagementPermission("monitor") permission
      return Collections.emptyList();
    }
  }

  /** @return a command to start a new JVM process to execute the given main class. */
  private static List<String> getCommand(
      boolean wrapperScript, Path artifact, boolean isDebug, String[] args) throws IOException {
    List<String> cmd = new ArrayList<>();

    // Check if wrapper uses script that provides java runtime by itself
    boolean javaRuntimeProvided =
        wrapperScript && "true".equalsIgnoreCase(getEnvValue("FAT_JAR_SKIP_ADDING_JAVA_RUNTIME"));
    if (!javaRuntimeProvided) {
      // Look for the Java binary given in an alternate location if given,
      // otherwise use the Java binary that started us
      String javaHome =
          System.getProperty("buck.fatjar.java.home", System.getProperty("java.home"));
      cmd.add(Paths.get(javaHome, "bin", "java").toString());
    }
    // Pass through any VM arguments to the child process
    cmd.addAll(getJVMArguments());
    if (!cmd.contains("-XX:-MaxFDLimit")) {
      // Directs the VM to refrain from setting the file descriptor limit to the default maximum.
      // https://stackoverflow.com/a/16535804/5208808
      cmd.add("-XX:-MaxFDLimit");
    }

    if (wrapperScript) {

      List<String> nonJvmArgs = new ArrayList<>();
      if (isDebug) {
        printDebugInfo(
            "extracting -J prefix args for JVM positional options for generate_wrapper format");
      }

      for (int i = 0; i < args.length; i++) {
        if (args[i].startsWith("-J")) {
          if (isDebug) {
            printDebugInfo("extracting arg: " + args[i].substring(2));
          }
          cmd.add(args[i].substring(2));
        } else {
          nonJvmArgs.add(args[i]);
        }
      }

      args = nonJvmArgs.toArray(new String[0]);

      String customWrapper = getEnvValue("FAT_JAR_CUSTOM_SCRIPT");
      if (customWrapper != null) {
        if (isDebug) {
          printDebugInfo("Using custom script wrapper: " + customWrapper);
        }
        cmd.add(0, customWrapper);
      }

      List<String> strings = Files.readAllLines(artifact, StandardCharsets.UTF_8);
      if (strings.size() != 1) {
        throw new IllegalStateException(
            String.format(
                "Expected to read only 1 line from the wrapper script: %s, but read: %s",
                artifact, strings.size()));
      }
      String command = strings.iterator().next();
      String[] wrapperCommand = command.split("\\s+");

      // classpath
      cmd.add(wrapperCommand[1]);
      cmd.add(wrapperCommand[2]);
      if (wrapperCommand.length > 4) {
        // main class
        String mainClass = wrapperCommand[3];
        cmd.add(mainClass);
      }
    } else {
      cmd.add("-jar");
      // Lookup our current JAR context.
      cmd.add(artifact.toString());
    }
    // pass args to new java process
    Collections.addAll(cmd, args);

    /* On Windows, we need to escape the arguments we hand off to `CreateProcess`.  See
     * http://blogs.msdn.com/b/twistylittlepassagesallalike/archive/2011/04/23/everyone-quotes-arguments-the-wrong-way.aspx
     * for more details.
     */
    if (isWindowsOs(getOsPlatform())) {
      List<String> escapedCommand = new ArrayList<>(cmd.size());
      for (String c : cmd) {
        escapedCommand.add(WindowsCreateProcessEscape.quote(c));
      }
      return escapedCommand;
    }

    return cmd;
  }

  private static void makeExecutable(Path file) throws IOException {
    if (!file.toFile().setExecutable(true, true)) {
      throw new IOException("The file could not be made executable");
    }
  }

  /**
   * @return the platform specific environment variable for setting the native library search path.
   */
  private static String getLibrarySearchPathName() {
    String platform = getOsPlatform();
    if (platform.startsWith("Linux")) {
      return "LD_LIBRARY_PATH";
    } else if (platform.startsWith("Mac OS")) {
      return "DYLD_LIBRARY_PATH";
    } else if (isWindowsOs(platform)) {
      return "PATH";
    } else {
      System.err.println(
          "WARNING: using \"LD_LIBRARY_PATH\" for unrecognized platform " + platform);
      return "LD_LIBRARY_PATH";
    }
  }

  // Avoid using EnvVariablesProvider to avoid extra dependencies.
  @SuppressWarnings("PMD.BlacklistedSystemGetenv")
  private static String getEnvValue(String envVariableName) {
    if (isWindowsOs(getOsPlatform())) {
      return findMapValueIgnoreKeyCase(envVariableName, System.getenv());
    } else {
      return System.getenv(envVariableName);
    }
  }

  private static String findMapValueIgnoreKeyCase(String key, Map<String, String> map) {
    for (Map.Entry<String, String> entry : map.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(key)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String getOsPlatform() {
    return Objects.requireNonNull(System.getProperty("os.name"));
  }

  private static boolean isWindowsOs(String osPlatform) {
    return osPlatform.startsWith("Windows");
  }

  private static void printDebugInfo(String message) {
    System.out.println("DEBUG: " + message);
  }

  /**
   * A temporary directory that automatically cleans itself up when used via a try-resource block.
   */
  private static class ManagedTemporaryDirectory implements AutoCloseable {

    private final Path path;
    private final boolean removeTempDirectory;

    private ManagedTemporaryDirectory(Path path, boolean removeTempDirectory) {
      this.path = path;
      this.removeTempDirectory = removeTempDirectory;
    }

    public ManagedTemporaryDirectory(String prefix, boolean removeTempDirectory)
        throws IOException {
      this(Files.createTempDirectory(prefix), removeTempDirectory);
    }

    @Override
    public void close() throws IOException {
      if (!removeTempDirectory) {
        printDebugInfo("Skipping temp directory removal. Temp directory: " + path);
        return;
      }
      Files.walkFileTree(
          path,
          new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
              if (e != null) {
                throw e;
              }
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    }

    public Path getPath() {
      return path;
    }
  }
}
