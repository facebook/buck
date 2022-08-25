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

package com.facebook.buck.installer.android;

import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Constructs configurations for an android install that are set in install_android_options.json and
 * sent to the AndroidInstaller
 */
public class AndroidInstallApkOptions {

  public final boolean restartAdbOnFailure;
  public final boolean skipInstallMetadata;
  public final boolean alwaysUseJavaAgent;
  public final boolean isZstdCompressionEnabled;
  public final int agentPortBase;

  AndroidInstallApkOptions(Path jsonArtifactPath) throws RuntimeException, IOException {
    JsonParser parser = ObjectMappers.createParser(jsonArtifactPath);
    Map<String, String> jsonData =
        parser.readValueAs(new TypeReference<TreeMap<String, String>>() {});
    this.restartAdbOnFailure = readBoolean(jsonData, "adb_restart_on_failure");
    this.skipInstallMetadata = readBoolean(jsonData, "skip_install_metadata");
    this.alwaysUseJavaAgent = readBoolean(jsonData, "skip_install_metadata");
    this.isZstdCompressionEnabled = readBoolean(jsonData, "is_zstd_compression_enabled");
    this.agentPortBase = readInt(jsonData, "agent_port_base", 2828);
  }

  private boolean readBoolean(Map<String, String> jsonData, String name) {
    return Boolean.parseBoolean(jsonData.getOrDefault(name, Boolean.FALSE.toString()));
  }

  private int readInt(Map<String, String> jsonData, String name, int defaultValue) {
    String readValue = jsonData.getOrDefault(name, "");
    if (readValue.isEmpty()) {
      return defaultValue;
    }
    return Integer.parseInt(readValue);
  }
}
