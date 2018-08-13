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

package com.facebook.buck.core.module.impl;

import com.facebook.buck.core.module.BuckModuleManager;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.lang.reflect.Field;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDependency;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;

public class DefaultBuckModuleManager implements BuckModuleManager {

  private final PluginManager pluginManager;
  private final BuckModuleJarHashProvider moduleJarHashProvider;

  private final Field pluginDescriptorField;

  private final LoadingCache<String, String> moduleHashCache =
      CacheBuilder.newBuilder()
          .maximumSize(1024)
          .build(
              new CacheLoader<String, String>() {
                @Override
                public String load(String pluginId) {
                  Hasher hasher = Hashing.murmur3_128().newHasher();
                  PluginWrapper pluginWrapper = pluginManager.getPlugin(pluginId);
                  pluginWrapper
                      .getDescriptor()
                      .getDependencies()
                      .forEach(
                          pluginDependency ->
                              hasher.putUnencodedChars(
                                  moduleHashCache.getUnchecked(pluginDependency.getPluginId())));
                  hasher.putUnencodedChars(getBuckModuleJarHash(pluginId));
                  return hasher.hash().toString();
                }
              });

  public DefaultBuckModuleManager(
      PluginManager pluginManager, BuckModuleJarHashProvider moduleJarHashProvider) {
    this.pluginManager = pluginManager;
    this.moduleJarHashProvider = moduleJarHashProvider;

    try {
      pluginDescriptorField = PluginClassLoader.class.getDeclaredField("pluginDescriptor");
      pluginDescriptorField.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new RuntimeException(
          "Cannot find pluginDescriptor field in " + PluginClassLoader.class.getName());
    }
  }

  @Override
  public boolean isClassInModule(Class<?> cls) {
    return cls.getClassLoader() instanceof PluginClassLoader;
  }

  @Override
  public String getModuleHash(Class<?> cls) {
    return getModuleHash(getPluginIdByClass(cls));
  }

  @Override
  public String getModuleHash(String moduleId) {
    return moduleHashCache.getUnchecked(moduleId);
  }

  @Override
  public ImmutableSortedSet<String> getModuleIds() {
    ImmutableSortedSet.Builder<String> moduleIds = ImmutableSortedSet.naturalOrder();
    for (PluginWrapper pluginWrapper : pluginManager.getPlugins()) {
      moduleIds.add(pluginWrapper.getPluginId());
    }
    return moduleIds.build();
  }

  @Override
  public ImmutableSortedSet<String> getModuleDependencies(String moduleId) {
    ImmutableSortedSet.Builder<String> dependencies = ImmutableSortedSet.naturalOrder();

    for (PluginDependency dependency :
        pluginManager.getPlugin(moduleId).getDescriptor().getDependencies()) {
      dependencies.add(dependency.getPluginId());
    }

    return dependencies.build();
  }

  private String getPluginIdByClass(Class<?> cls) {
    ClassLoader classLoader = cls.getClassLoader();
    if (!(classLoader instanceof PluginClassLoader)) {
      throw new IllegalStateException(
          String.format(
              "Requested module hash for class %s but its class loader is not PluginClassCloader",
              cls.getName()));
    }
    return getPluginDescriptorFromClassLoader((PluginClassLoader) classLoader).getPluginId();
  }

  private PluginDescriptor getPluginDescriptorFromClassLoader(PluginClassLoader pluginClassLoader) {
    try {
      return (PluginDescriptor) pluginDescriptorField.get(pluginClassLoader);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(
          "pluginDescriptor field is not accessible in " + PluginClassLoader.class.getName());
    }
  }

  private String getBuckModuleJarHash(String pluginId) {
    return moduleJarHashProvider.getModuleHash(pluginManager.getPlugin(pluginId));
  }
}
