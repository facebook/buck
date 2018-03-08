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

package com.facebook.buck.module.impl.modulewithdeps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.module.BuckModuleManager;
import com.facebook.buck.module.impl.BuckModuleJarHashProvider;
import com.facebook.buck.module.impl.DefaultBuckModuleManager;
import com.facebook.buck.module.impl.TestExtension;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.Serializable;
import java.util.List;
import org.pf4j.PluginManager;

public class ModuleWithDependenciesTest {

  private static final String HASH = "some content that never changes";

  public static void main(String... args) {
    List<TestExtension> testExtensions = loadTestExtensionsFromPlugin();

    testBuckModuleHashProvider(testExtensions);
  }

  private static List<TestExtension> loadTestExtensionsFromPlugin() {
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    return pluginManager.getExtensions(TestExtension.class);
  }

  private static void testBuckModuleHashProvider(List<TestExtension> testExtensions) {
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    BuckModuleJarHashProvider hashProvider = new BuckModuleJarHashProvider();
    BuckModuleManager moduleManager = new DefaultBuckModuleManager(pluginManager, hashProvider);

    TestExtension testExtension1 = testExtensions.get(0);
    TestExtension testExtension2 = testExtensions.get(1);

    String hash1 = moduleManager.getModuleHash(testExtension1.getClass());
    String hash2 = moduleManager.getModuleHash(testExtension2.getClass());

    assertNotEquals(hash1, hash2);

    TestExtension extensionFromDependentModule;
    TestExtension extensionFromIndependentModule;

    if (testExtension1 instanceof Serializable) {
      extensionFromDependentModule = testExtension2;
      extensionFromIndependentModule = testExtension1;
    } else {
      extensionFromDependentModule = testExtension1;
      extensionFromIndependentModule = testExtension2;
    }

    assertEquals(
        hash(HASH), moduleManager.getModuleHash(extensionFromIndependentModule.getClass()));
    assertEquals(
        hash(hash(HASH), HASH),
        moduleManager.getModuleHash(extensionFromDependentModule.getClass()));
  }

  private static String hash(String... content) {
    Hasher hasher = Hashing.murmur3_128().newHasher();
    for (String data : content) {
      hasher.putUnencodedChars(data);
    }
    return hasher.hash().toString();
  }
}
