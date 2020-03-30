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

package com.facebook.buck.cli.bootstrapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Utility class for {@link ClassLoaderBootstrapper} */
class ClassLoaderBootstrapperUtils {

  private ClassLoaderBootstrapperUtils() {}

  static void invokeMainMethod(ClassLoader classLoader, String mainClassName, String[] args) {
    Method mainMethod = getMainMethod(classLoader, mainClassName);
    try {
      mainMethod.invoke(null, (Object) args);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Can't access main() method.", e);
    } catch (InvocationTargetException e) {
      throw new IllegalStateException("Exception during main() method execution", e.getCause());
    }
  }

  private static Method getMainMethod(ClassLoader classLoader, String mainClassName) {
    Class<?> mainClass = loadClassWithMainFunction(classLoader, mainClassName);
    try {
      return mainClass.getMethod("main", String[].class);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("Can't find a main() method in class: " + mainClassName, e);
    }
  }

  private static Class<?> loadClassWithMainFunction(ClassLoader classLoader, String mainClassName) {
    try {
      return classLoader.loadClass(mainClassName);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("Can't find class: " + mainClassName, e);
    }
  }
}
