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

package com.facebook.buck.jvm.java.javax;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * ToolProvider has no synchronization internally, so if we don't synchronize from the outside we
 * could wind up loading the compiler classes multiple times from different class loaders.
 */
public class SynchronizedToolProvider {

  public static JavaCompiler getSystemJavaCompiler() {
    JavaCompiler compiler;
    synchronized (ToolProvider.class) {
      compiler = ToolProvider.getSystemJavaCompiler();
    }
    return compiler;
  }

  public static ClassLoader getSystemToolClassLoader() {
    ClassLoader classLoader;
    synchronized (ToolProvider.class) {
      classLoader = ToolProvider.getSystemToolClassLoader();
    }
    return classLoader;
  }
}
