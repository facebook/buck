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

  private static final String DEP_TRACKER_KOTLINC_PLUGIN_JAR_RESOURCE_PATH = "dep-tracker.jar";

  public static final File DEP_TRACKER_KOTLINC_PLUGIN_JAR_PATH = extractDepTrackerPlugin();

  private static File extractDepTrackerPlugin() {
    try {
      return doExtractDepTrackerPlugin();
    } catch (URISyntaxException | IOException | NullPointerException e) {
      throw new RuntimeException("Failed to extract kotlinc plugin jar", e);
    }
  }

  private static File doExtractDepTrackerPlugin() throws URISyntaxException, IOException {
    URL resource = PluginLoader.class.getResource(DEP_TRACKER_KOTLINC_PLUGIN_JAR_RESOURCE_PATH);
    if (resource == null) {
      throw new RuntimeException("Could not find dep-tracker kotlinc plugin jar");
    }
    if ("file".equals(resource.getProtocol())) {
      return new File(resource.toURI());
    }
    try (InputStream in =
        PluginLoader.class.getResourceAsStream(DEP_TRACKER_KOTLINC_PLUGIN_JAR_RESOURCE_PATH)) {
      File plugin = File.createTempFile("dep-tracker", ".jar");
      plugin.deleteOnExit();
      Files.copy(in, Paths.get(plugin.toURI()), StandardCopyOption.REPLACE_EXISTING);
      return plugin;
    }
  }
}
