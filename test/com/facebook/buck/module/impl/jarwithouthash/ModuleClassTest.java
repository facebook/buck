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

import com.facebook.buck.module.BuckModuleManager;
import com.facebook.buck.module.impl.BuckModuleJarHashProvider;
import com.facebook.buck.module.impl.DefaultBuckModuleManager;
import com.facebook.buck.module.impl.TestExtension;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.lang.reflect.Field;
import java.util.List;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;

public class ModuleClassTest {

  public static void main(String... args) throws Exception {
    testBuckModuleHashProvider();
  }

  private static void testBuckModuleHashProvider() throws Exception {
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();

    List<TestExtension> extensions = pluginManager.getExtensions(TestExtension.class);

    assertEquals(1, extensions.size());

    TestExtension testExtension = extensions.get(0);

    BuckModuleJarHashProvider hashProvider = new BuckModuleJarHashProvider();
    BuckModuleManager moduleManager = new DefaultBuckModuleManager(pluginManager, hashProvider);

    try {
      moduleManager.getModuleHash(testExtension.getClass());
    } catch (UncheckedExecutionException e) {
      assertEquals(
          "Could not load module binary hash for "
              + "module loaded in plugin "
              + getPluginWrapperForClass(pluginManager, testExtension.getClass()),
          e.getCause().getMessage());
    }
  }

  private static PluginWrapper getPluginWrapperForClass(PluginManager pluginManager, Class<?> cls)
      throws Exception {
    Field pluginDescriptorField = PluginClassLoader.class.getDeclaredField("pluginDescriptor");
    pluginDescriptorField.setAccessible(true);
    PluginDescriptor pluginDescriptor =
        (PluginDescriptor) pluginDescriptorField.get(cls.getClassLoader());

    return pluginManager.getPlugin(pluginDescriptor.getPluginId());
  }
}
