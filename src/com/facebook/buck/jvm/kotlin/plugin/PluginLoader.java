/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.jvm.kotlin.plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class PluginLoader {

  private static final String KOTLINC_PLUGIN_JAR_RESOURCE_PATH = "kotlinc-plugin.jar";

  public static final File KOTLINC_PLUGIN_JAR_PATH = extractPlugin();

  private static File extractPlugin() {
    try {
      return doExtractPlugin();
    } catch (URISyntaxException | IOException | NullPointerException e) {
      throw new RuntimeException("Failed to extract kotlinc plugin jar", e);
    }
  }

  private static File doExtractPlugin() throws URISyntaxException, IOException {
    URL resource = PluginLoader.class.getResource(KOTLINC_PLUGIN_JAR_RESOURCE_PATH);
    if (resource == null) {
      throw new RuntimeException("Could not find javac kotlinc jar");
    }
    if ("file".equals(resource.getProtocol())) {
      return new File(resource.toURI());
    }
    try (InputStream in =
        PluginLoader.class.getResourceAsStream(KOTLINC_PLUGIN_JAR_RESOURCE_PATH)) {
      File plugin = File.createTempFile("kotlinc-plugin", ".jar");
      plugin.deleteOnExit();
      Files.copy(in, Paths.get(plugin.toURI()), StandardCopyOption.REPLACE_EXISTING);
      return plugin;
    }
  }
}
