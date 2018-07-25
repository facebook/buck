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

package com.facebook.buck.jvm.java.runner;

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
 * This class handles extra large classpaths by allowing the client to pass classpath via file
 * specified by {@code buck.classpath_file}. <b>WARNING: </b> this class creates a new classloader
 * on java 9+ and it modifies system property {@code java.class.path}.
 *
 * <p>This class needs {@code -Dbuck.testrunner_classes} system property set testrunner classpath to
 * work and accepts optional {@code -Dbuck.classpath_file} system property which should hold a file
 * with additional classpath entries.
 *
 * <p>This class has different behavior for java 8 and java 9+, neither is bullet-proof. On java 8
 * it adds entries from classpath_file to ClassLoader.getSystemClassLoader(). On java 9+ it creates
 * a new classloader with test_runner classes and all entries from classpath_file.
 *
 * <p>Note: this class only depends on classes present in the JRE, since we don't want to have to
 * push more things on to the classpath when using it.
 *
 * <p>TODO(jtokkola): creating child classloader will not work for buck tests that use javac
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
    if (System.getProperty(TESTRUNNER_CLASSES_PROPERTY) == null) {
      throw new IllegalArgumentException(
          String.format(
              "System property %s is required by %s",
              TESTRUNNER_CLASSES_PROPERTY, FileClassPathRunner.class.getSimpleName()));
    }

    StringBuilder classPathProperty = new StringBuilder();
    URL[] classpath = getClassPath(classPathProperty);
    System.setProperty("java.class.path", classPathProperty.toString());
    ClassLoader classLoader = getClassLoaderForTests(classpath);

    // Now read the main class from the args and invoke it
    Method main = getMainMethod(classLoader, args);
    String[] mainArgs = constructArgs(args);

    main.invoke(null, new Object[] {mainArgs});
  }

  private static ClassLoader getClassLoaderForTests(URL[] classpath) {
    String javaVersion = System.getProperty("java.version");
    if (javaVersion.startsWith("1.8.") || "1.8".equals(javaVersion)) {
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
                "Couldn't add classpath URLs to (URLClassLoader) ClassLoader.getSystemClassLoader() on java version %s",
                javaVersion),
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
    return Files.readAllLines(classpathFile)
        .stream()
        .map(Paths::get)
        .filter(Files::exists)
        .collect(Collectors.toList());
  }

  static Path getTestRunnerClassPath() {
    String path = System.getProperty(TESTRUNNER_CLASSES_PROPERTY);
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
