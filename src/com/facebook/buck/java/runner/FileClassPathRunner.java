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

package com.facebook.buck.java.runner;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * A standalone class that's designed to read its classpath from a file given using the standard `@`
 * syntax used by javac. <b>WARNING: </b> this class modifies the system classloader. Don't use this
 * in-process. This also modifies the System property {@code java.class.path}.
 *
 * <p>
 *
 * We rely on the fact that you can pass total garbage into the classpath, and it'll be set just
 * fine. Because of this, usage is like so:
 * <pre>java -classpath @path/to/classpath-file com.example.MainClass arg1 arg2 arg3</pre>
 *
 * <p>
 *
 * The format of the file used for adding new entries to the classpath is simply one entry per line.
 * Each entry is checked to see if it resolves to a valid path on the local file system. If it does
 * then a URL is constructed from that entry and added to the system classloader.
 *
 * <p>
 *
 * It is possible to declare more than one @classpathfile, and ordering and duplicates will be
 * honoured.
 *
 * <p>
 *
 * Note: this class only depends on classes present in the JRE, since we don't want to have to push
 * more things on to the classpath when using it.
 */
public class FileClassPathRunner {

  private FileClassPathRunner() {
    // Do not instantiate.
  }

  public static void main(String[] args) throws IOException, ReflectiveOperationException {
    // We must have the name of the class to delegate to added.
    if (args.length < 1) {
      System.exit(-1);
    }

    ClassLoader sysLoader = ClassLoader.getSystemClassLoader();
    if (!(sysLoader instanceof URLClassLoader)) {
      System.exit(-2);
    }

    URLClassLoader urlClassLoader = (URLClassLoader) sysLoader;

    modifyClassLoader(urlClassLoader, true);

    // Now read the main class from the args and invoke it
    Method main = getMainClass(args);
    String[] mainArgs = constructArgs(args);

    main.invoke(null, new Object[] { mainArgs });
  }

  static void modifyClassLoader(
      URLClassLoader urlClassLoader,
      boolean modifySystemClasspathProperty) throws IOException, ReflectiveOperationException {
    List<Path> paths = getClasspathFiles(urlClassLoader.getURLs());
    List<URL> readUrls = readUrls(paths, modifySystemClasspathProperty);
    addUrlsToClassLoader(urlClassLoader, readUrls);
  }

  // @VisibileForTesting
  @SuppressWarnings("PMD.EmptyCatchBlock")
  static List<Path> getClasspathFiles(URL[] urls) throws IOException {
    List<Path> paths = new LinkedList<>();

    for (URL url : urls) {
      if (!"file".equals(url.getProtocol())) {
        continue;
      }

      // Segment the path, looking for a section that starts with "@". If one is found, reconstruct
      // the rest of the path, and check to see if it's a file that's readable. If it's readable,
      // pull it into memory, and scan it for entries to add to the classpath, using the standard
      // format of "file/" to indicate a directory and assuming all other files are jars.

      // I assume that Windows segments paths in URLs using a forward slash.
      String[] split = url.getPath().split("@", 2);
      if (split.length != 2) {
        continue;
      }

      try {
        paths.add(Paths.get(split[1]));
      } catch (InvalidPathException e) {
        // Carry on regardless
      }
    }
    return paths;
  }

  // @VisibleForTesting
  @SuppressWarnings("PMD.EmptyCatchBlock")
  static List<URL> readUrls(List<Path> paths, boolean modifySystemClassPathProperty)
      throws IOException {
    List<URL> readUrls = new LinkedList<>();

    List<String> classPathEntries = new LinkedList<>();

    for (Path path : paths) {
      if (!Files.exists(path)) {
        continue;
      }

      List<String> lines = Files.readAllLines(path, UTF_8);
      for (String line : lines) {
        if (line.isEmpty()) {
          continue;
        }
        try {
          Path entry = Paths.get(line);
          readUrls.add(entry.toUri().toURL());
          classPathEntries.add(entry.toString());
        } catch (InvalidPathException e) {
          // Carry on regardless --- java allows us to put absolute garbage into the classpath.
        }
      }
    }

    StringBuilder newClassPath = new StringBuilder();
    constructNewClassPath(newClassPath, System.getProperty("java.class.path"), classPathEntries);

    if (modifySystemClassPathProperty) {
      System.setProperty("java.class.path", newClassPath.toString());
    }

    return readUrls;
  }

  // @VisibleForTesting
  static void constructNewClassPath(
      StringBuilder newClassPath,
      /* @Nullable */ String existingClassPath,
      List<String> classPathEntries) {

    if (existingClassPath != null) {
      newClassPath.append(existingClassPath);
    }

    Iterator<String> iterator = classPathEntries.iterator();
    if (iterator.hasNext()) {
      newClassPath.append(existingClassPath == null ? "" : File.pathSeparatorChar);
      newClassPath.append(iterator.next());
    }
    while (iterator.hasNext()) {
      newClassPath.append(File.pathSeparatorChar).append(iterator.next());
    }
  }

  private static void addUrlsToClassLoader(ClassLoader sysLoader, List<URL> readUrls)
      throws ReflectiveOperationException {
    // Add all the URLs to the classloader
    Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
    addURL.setAccessible(true);
    for (URL readUrl : readUrls) {
      addURL.invoke(sysLoader, readUrl);
    }
  }

  private static String[] constructArgs(String[] args) {
    String[] mainArgs;

    if (args.length == 1) {
      mainArgs = new String[0];
    } else {
      mainArgs = new String[args.length - 1];
      System.arraycopy(args, 1, mainArgs, 0, mainArgs.length);
    }
    return mainArgs;
  }

  private static Method getMainClass(String[] args) throws ReflectiveOperationException {
    Class<?> mainClazz = Class.forName(args[0]);
    Method main = mainClazz.getMethod("main", args.getClass());
    int modifiers = main.getModifiers();
    if (!Modifier.isStatic(modifiers)) {
      System.exit(-4);
    }
    return main;
  }
}
