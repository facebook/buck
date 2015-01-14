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

package com.facebook.buck.cli.bootstrapper;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.Arrays;

public final class ClassLoaderBootstrapper {
  private static ClassLoader classLoader;

  private ClassLoaderBootstrapper() {
  }

  public static void main(String[] args) throws Exception {
    String classPath = System.getenv("BUCK_CLASSPATH");
    if (classPath == null) {
      throw new RuntimeException("BUCK_CLASSPATH not set");
    }

    String mainClassName = args[0];
    String[] remainingArgs = Arrays.copyOfRange(args, 1, args.length);
    classLoader = createClassLoader(classPath);

    // Some things (notably Jetty) use the context class loader to load stuff
    Thread.currentThread().setContextClassLoader(classLoader);

    Class<?> mainClass = classLoader.loadClass(mainClassName);
    Method mainMethod = mainClass.getMethod("main", String[].class);
    mainMethod.invoke(null, (Object) remainingArgs);
  }

  public static Class<?> loadClass(String name) {
    try {
      return classLoader.loadClass(name);
    } catch (ClassNotFoundException e) {
      throw new NoClassDefFoundError(name);
    }
  }

  private static ClassLoader createClassLoader(String classPath)
      throws MalformedURLException {
    String[] strings = classPath.split(File.pathSeparator);
    URL[] urls = new URL[strings.length];
    for (int i = 0; i < urls.length; i++) {
      urls[i] = Paths.get(strings[i]).toUri().toURL();
    }

    return new URLClassLoader(urls);
  }
}
