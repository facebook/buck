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

package com.facebook.buck.core.module.impl.nonmoduleclass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import com.facebook.buck.core.module.BuckModuleManager;
import com.facebook.buck.core.module.impl.BuckModuleJarHashProvider;
import com.facebook.buck.core.module.impl.DefaultBuckModuleManager;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import org.pf4j.PluginManager;

public class NonModuleClassTest {

  public static void main(String... args) {
    testBuckModuleHashProvider();
  }

  private static void testBuckModuleHashProvider() {
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    BuckModuleJarHashProvider hashProvider = new BuckModuleJarHashProvider();
    BuckModuleManager moduleManager = new DefaultBuckModuleManager(pluginManager, hashProvider);

    try {
      moduleManager.getModuleHash(NonModuleClassTest.class);
      fail("Should throw IllegalStateException");
    } catch (IllegalStateException e) {
      assertEquals(
          "Requested module hash for class "
              + "com.facebook.buck.core.module.impl.nonmoduleclass.NonModuleClassTest but "
              + "its class loader is not PluginClassCloader",
          e.getMessage());
    }

    assertFalse(moduleManager.isClassInModule(NonModuleClassTest.class));
  }
}
