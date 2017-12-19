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

package com.facebook.buck.plugin.impl;

import org.pf4j.PluginManager;

/**
 * Creates instances of {@link PluginManager} that are able to find extensions in Buck.
 *
 * <p>These should be one instance of {@link PluginManager} in a running app.
 */
public class BuckPluginManagerFactory {

  /** Creates a {@link PluginManager} and loads plugins. */
  public static PluginManager createPluginManager() {
    PluginManager pluginManager = new DefaultBuckPluginManager();
    pluginManager.loadPlugins();
    pluginManager.startPlugins();

    return pluginManager;
  }
}
