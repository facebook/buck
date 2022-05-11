/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

public class PluginLoaderJavaFileManager extends ForwardingStandardJavaFileManager
    implements StandardJavaFileManager {

  private final JavacExecutionContext context;
  private final PluginFactory pluginFactory;
  private final JavacPluginParams javacPlugins;

  /**
   * Creates a new instance of ForwardingJavaFileManager.
   *
   * @param fileManager delegate to this file manager
   * @param javacPlugins
   */
  protected PluginLoaderJavaFileManager(
      JavacExecutionContext context,
      StandardJavaFileManager fileManager,
      PluginFactory pluginFactory,
      JavacPluginParams javacPlugins) {
    super(fileManager);
    this.context = context;
    this.pluginFactory = pluginFactory;
    this.javacPlugins = javacPlugins;
  }

  @Override
  public ClassLoader getClassLoader(Location location) {
    // We only provide the shared classloader if there are plugins defined.
    if (StandardLocation.ANNOTATION_PROCESSOR_PATH.equals(location)
        && javacPlugins.getPluginProperties().size() > 0) {
      return pluginFactory.getClassLoaderForProcessorGroups(
          javacPlugins, context.getRuleCellRoot());
    }
    return super.getClassLoader(location);
  }
}
