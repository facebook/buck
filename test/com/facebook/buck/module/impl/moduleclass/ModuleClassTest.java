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

package com.facebook.buck.module.impl.moduleclass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.module.BuckModuleManager;
import com.facebook.buck.module.impl.BuckModuleJarHashProvider;
import com.facebook.buck.module.impl.DefaultBuckModuleManager;
import com.facebook.buck.module.impl.TestExtension;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.util.List;
import org.pf4j.PluginManager;

public class ModuleClassTest {

  public static void main(String... args) {
    TestExtension testExtension = loadTestExtensionFromPlugin();

    testBuckModuleHashProvider(testExtension);
  }

  private static TestExtension loadTestExtensionFromPlugin() {
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    List<TestExtension> extensions = pluginManager.getExtensions(TestExtension.class);
    assertEquals(1, extensions.size());
    return extensions.get(0);
  }

  private static void testBuckModuleHashProvider(TestExtension testExtension) {
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    BuckModuleJarHashProvider hashProvider = new BuckModuleJarHashProvider();
    BuckModuleManager moduleManager = new DefaultBuckModuleManager(pluginManager, hashProvider);
    Hasher hasher = Hashing.murmur3_128().newHasher();
    hasher.putUnencodedChars("some content that never changes");
    assertEquals(hasher.hash().toString(), moduleManager.getModuleHash(testExtension.getClass()));

    assertTrue(moduleManager.isClassInModule(testExtension.getClass()));
    assertFalse(moduleManager.isClassInModule(ModuleClassTest.class));
  }
}
