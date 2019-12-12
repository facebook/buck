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

package com.facebook.buck.jvm.java.runner;

import com.facebook.buck.jvm.java.version.JavaVersion;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This class handles extra large classpaths by allowing the client to pass the classpath via file
 * specified by {@code buck.classpath_file}. This is to sidestep commandline length limits.
 *
 * <p>There are 3 modes:
 *
 * <ul>
 *   <li>Java 8 or older launched: We manually add the entries from `buck.classpath_file` to the
 *       system classloader.
 *   <li>Java 9 or newer launched, and Buck is aware of this: We rely on `@argfile` support in newer
 *       versions of `java` to pass in our long `-classpath` argument. Here, the JVM adds all the
 *       classpath entries itself.
 *   <li>Java 9 or newer launched, and Buck isn't aware of this: We create a new classloader and add
 *       entries from `buck.classpath_file` manually. This approach has problems, e.g. classloading
 *       for in-process Java compilation does not work for Buck's own tests, but is provided to
 *       support cases where the version of `java` on the path is 9+, but we're targeting 8- for the
 *       target under test.
 * </ul>
 *
 * <p>Note: this class only depends on classes present in the JRE, since we don't want to have to
 * push more things on to the classpath when using it.
 */
public class FileClassPathRunner {
  public static final String CLASSPATH_FILE_PROPERTY = "buck.classpath_file";
  public static final String TESTRUNNER_CLASSES_PROPERTY = "buck.testrunner_classes";

  private FileClassPathRunner() {
    // Do not instantiate.
  }

  public static void main(String[] args) throws IOException, ReflectiveOperationException {
    // We must have the name of the class to delegate to added.
    if (args.length < 1) {
      System.exit(-1);
    }

    ClassLoader classLoader;
    if (shouldPopulateClassLoader()) {
      // We need to manually populate the classloader from a file containing the (potentially very
      // long) classpath. This happens either because we're running on Java 8 or older, which do
      // not support argfiles in `java` invocations, or we're running on Java 9 or later, but Buck
      // isn't aware that it's launching a version of Java newer than 8.
      StringBuilder classPathProperty = new StringBuilder();
      URL[] classpath = getClassPath(classPathProperty);
      System.setProperty("java.class.path", classPathProperty.toString());
      classLoader = getClassLoaderForTests(classpath);
    } else {
      // We've passed an argfile containing our full (potentially very long) classpath into `java`,
      // which the JVM has already populated the system classloader with. Nothing to do.
      classLoader = ClassLoader.getSystemClassLoader();
    }

    // Now read the main class from the args and invoke it
    Method main = getMainMethod(classLoader, args);
    String[] mainArgs = constructArgs(args);
    main.invoke(null, new Object[] {mainArgs});
  }

  private static boolean shouldPopulateClassLoader() {
    return JavaVersion.getMajorVersion() <= 8
        || System.getProperty(TESTRUNNER_CLASSES_PROPERTY) != null;
  }

  private static String readRequiredProperty(String propertyName) {
    String value = System.getProperty(propertyName);
    if (value == null) {
      throw new IllegalArgumentException(
          String.format(
              "System property %s is required by %s",
              propertyName, FileClassPathRunner.class.getSimpleName()));
    }
    return value;
  }

  private static ClassLoader getClassLoaderForTests(URL[] classpath) {
    if (JavaVersion.getMajorVersion() <= 8) {
      URLClassLoader urlClassLoader = (URLClassLoader) ClassLoader.getSystemClassLoader();
      try {
        Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
        addURL.setAccessible(true);
        for (URL entry : classpath) {
          addURL.invoke(urlClassLoader, entry);
        }
      } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException(
            String.format(
                "Couldn't add classpath URLs to (URLClassLoader) ClassLoader.getSystemClassLoader() on java version %d",
                JavaVersion.getMajorVersion()),
            e);
      }
      return urlClassLoader;
    } else {
      ClassLoader classLoader = URLClassLoader.newInstance(classpath, findPlatformClassLoader());
      Thread.currentThread().setContextClassLoader(classLoader);
      return classLoader;
    }
  }

  // VisibleForTesting (can not use guava as dependency)
  static URL[] getClassPath(StringBuilder classPathPropertyOut) throws IOException {
    List<Path> classPath = new ArrayList<>();
    classPath.add(getTestRunnerClassPath());

    String classPathFileProperty = System.getProperty(CLASSPATH_FILE_PROPERTY);
    if (classPathFileProperty != null) {
      classPath.addAll(getTestClassPath(Paths.get(classPathFileProperty)));
    }

    URL[] result = new URL[classPath.size()];
    for (int i = 0; i < result.length; i++) {
      Path entry = classPath.get(i);
      result[i] = entry.toUri().toURL();

      classPathPropertyOut.append(entry);
      classPathPropertyOut.append(":");
    }
    classPathPropertyOut.deleteCharAt(classPathPropertyOut.length() - 1);
    return result;
  }

  // VisibleForTesting (can not use guava as dependency)
  static List<Path> getTestClassPath(Path classpathFile) throws IOException {
    return Files.readAllLines(classpathFile).stream()
        .map(Paths::get)
        .filter(Files::exists)
        .collect(Collectors.toList());
  }

  static Path getTestRunnerClassPath() {
    String path = readRequiredProperty(TESTRUNNER_CLASSES_PROPERTY);
    return Paths.get(path);
  }

  // VisibleForTesting (can not use guava as dependency)
  static String[] constructArgs(String[] args) {
    String[] mainArgs;

    if (args.length == 1) {
      mainArgs = new String[0];
    } else {
      mainArgs = new String[args.length - 1];
      System.arraycopy(args, 1, mainArgs, 0, mainArgs.length);
    }
    return mainArgs;
  }

  private static Method getMainMethod(ClassLoader classLoader, String[] args)
      throws ReflectiveOperationException {
    Class<?> mainClazz = classLoader.loadClass(args[0]);
    Method main = mainClazz.getMethod("main", args.getClass());
    int modifiers = main.getModifiers();
    if (!Modifier.isStatic(modifiers)) {
      System.exit(-4);
    }
    return main;
  }

  /**
   * Returns ClassLoader that is a sibling on "current" class loader, in java 8 that is "Ext" class
   * loader, on java 9+ that is "platform" classloader.
   */
  // VisibleForTesting (can not use guava as dependency)
  static ClassLoader findPlatformClassLoader() {
    try {
      // use platform class loader on Java 9+ if exists
      Method method = ClassLoader.class.getMethod("getPlatformClassLoader");
      return (ClassLoader) method.invoke(null);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new AssertionError("Cannot find platform class loader", e);
    }
  }
}
