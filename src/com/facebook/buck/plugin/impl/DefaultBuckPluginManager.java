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

import com.facebook.buck.plugin.BuckPluginManager;
import org.pf4j.CompoundPluginDescriptorFinder;
import org.pf4j.DefaultPluginManager;
import org.pf4j.ExtensionFinder;
import org.pf4j.JarPluginLoader;
import org.pf4j.ManifestPluginDescriptorFinder;
import org.pf4j.PluginLoader;
import org.pf4j.VersionManager;

public class DefaultBuckPluginManager extends DefaultPluginManager implements BuckPluginManager {
  @Override
  protected ExtensionFinder createExtensionFinder() {
    return new BuckExtensionFinder(this);
  }

  @Override
  protected CompoundPluginDescriptorFinder createPluginDescriptorFinder() {
    return new CompoundPluginDescriptorFinder().add(new ManifestPluginDescriptorFinder());
  }

  @Override
  protected PluginLoader createPluginLoader() {
    return new JarPluginLoader(this);
  }

  @Override
  protected VersionManager createVersionManager() {
    // Buck modules do not support versions
    return (__, ___) -> true;
  }
}
