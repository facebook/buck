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

package com.facebook.buck.module.impl.jarwithouthash;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.module.impl.BuckModuleHashProvider;
import com.facebook.buck.module.impl.TestExtension;
import com.facebook.buck.plugin.BuckPluginManagerFactory;
import java.io.IOException;
import java.util.List;
import org.pf4j.PluginManager;

public class ModuleClassTest {

  public static void main(String... args) throws IOException {
    testBuckModuleHashProvider();
  }

  private static void testBuckModuleHashProvider() {
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();

    List<TestExtension> extensions = pluginManager.getExtensions(TestExtension.class);

    assertEquals(1, extensions.size());

    TestExtension testExtension = extensions.get(0);

    BuckModuleHashProvider hashProvider = new BuckModuleHashProvider();

    try {
      hashProvider.getModuleHash(testExtension.getClass());
    } catch (IllegalStateException e) {
      assertEquals(
          "Could not load module binary hash for class class "
              + "com.facebook.buck.module.impl.jarwithouthash.test_module.TestModuleExtension",
          e.getMessage());
    }
  }
}
