/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.module.impl;

import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import org.pf4j.PluginClassLoader;

public class BuckModuleHashProvider {

  private static final String MODULE_BINARY_HASH_LOCATION = "META-INF/module-binary-hash.txt";

  public boolean canProvideModuleHash(Class<?> cls) {
    return cls.getClassLoader() instanceof PluginClassLoader;
  }

  public String getModuleHash(Class<?> cls) {
    return getModuleJarBinaryHash(cls);
  }

  private String getModuleJarBinaryHash(Class<?> cls) {
    ClassLoader classLoader = cls.getClassLoader();

    URL binaryHashLocation = classLoader.getResource(MODULE_BINARY_HASH_LOCATION);

    if (binaryHashLocation == null) {
      throw new IllegalStateException("Could not load module binary hash for class " + cls);
    }

    try {
      return Resources.toString(binaryHashLocation, Charset.defaultCharset());
    } catch (IOException e) {
      throw new RuntimeException(String.format("Can't load module hash for class %s", cls), e);
    }
  }
}
