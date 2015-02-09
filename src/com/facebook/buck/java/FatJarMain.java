/*
 * Copyright 2014-present Facebook, Inc.
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

/***************
 *
 * This code can be embedded in arbitrary third-party projects!
 * For maximum compatibility, use only Java 6 constructs.
 *
 ***************/

package com.facebook.buck.java;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class FatJarMain {

  private FatJarMain() {}

  /**
   * Update the library search path environment variable with the given native library directory.
   */
  private static void updateEnvironment(Map<String, String> env, Path libDir) {
    String librarySearchPathName = getLibrarySearchPathName();
    String originalLibPath = System.getenv(librarySearchPathName);
    String newlibPath =
        libDir.toString() + (originalLibPath == null ? "" : File.pathSeparator + originalLibPath);
    env.put(librarySearchPathName, newlibPath);
  }

  /**
   * @return a command to start a new JVM process to execute the given main class.
   */
  private static List<String> getCommand(Path jar, String[] args) throws Exception {
    List<String> cmd = new ArrayList<String>();
    // Lookup the Java binary used to start us.
    cmd.add(Paths.get(System.getProperty("java.home")).resolve("bin").resolve("java").toString());
    cmd.add("-jar");
    // Lookup our current JAR context.
    cmd.add(jar.toString());
    Collections.addAll(cmd, args);
    return cmd;
  }

  public static void main(String[] args) throws Exception {
    Class<?> clazz = FatJarMain.class;
    ClassLoader classLoader = clazz.getClassLoader();

    // Load the fat jar info from it's resource.
    FatJar fatJar = FatJar.load(classLoader);

    ManagedTemporaryDirectory temp = new ManagedTemporaryDirectory("fatjar.");

    // Create a temp dir to house the native libraries.
    try {

      // Unpack the real, inner JAR.
      Path jar = temp.getPath().resolve("main.jar");
      fatJar.unpackJarTo(classLoader, jar);

      // Unpack all the native libraries, since the system loader will need to find these
      // on disk.
      Path nativeLibs = temp.getPath().resolve("nativelibs");
      Files.createDirectory(nativeLibs);
      fatJar.unpackNativeLibrariesInto(classLoader, temp.getPath());

      // Update the appropriate environment variable with the location of our native libraries
      // and start the real main class in a new process so that it picks it up.
      ProcessBuilder builder = new ProcessBuilder();
      builder.command(getCommand(jar, args));
      updateEnvironment(builder.environment(), temp.getPath());
      builder.inheritIO();

      // Wait for the inner process to finish, and propagate it's exit code, before cleaning
      // up the native libraries.
      System.exit(builder.start().waitFor());
    } finally {
      temp.close();
    }
  }

  /**
   * @return the platform specific environment variable for setting the native library search path.
   */
  private static String getLibrarySearchPathName() {
    String platform = System.getProperty("os.name");
    if (platform.startsWith("Linux")) {
      return "LD_LIBRARY_PATH";
    } else if (platform.startsWith("Mac OS")) {
      return "DYLD_LIBRARY_PATH";
    } else if (platform.startsWith("Windows")) {
      return "PATH";
    } else {
      System.err.println(
          "WARNING: using \"LD_LIBRARY_PATH\" for unrecognized platform " + platform);
      return "LD_LIBRARY_PATH";
    }
  }

  /**
   * A temporary directory that automatically cleans itself up when used via a try-resource block.
   */
  private static class ManagedTemporaryDirectory implements AutoCloseable {

    private final Path path;

    private ManagedTemporaryDirectory(Path path) {
      this.path = path;
    }

    public ManagedTemporaryDirectory(String prefix, FileAttribute<?>... attrs) throws IOException {
      this(Files.createTempDirectory(prefix, attrs));
    }

    @Override
    public void close() throws IOException {
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
