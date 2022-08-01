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

package com.facebook.buck.installer;

import com.google.common.util.concurrent.SettableFuture;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Constructs configurations for an android install that are set in install_android_options.json and
 * sent to the AndroidInstaller
 */
class AndroidInstallApkOptions {
  public boolean restartAdbOnFailure;
  public boolean skipInstallMetadata;
  public boolean alwayUseJavaAgent;
  public boolean isZstdCompressionEnabled;
  public int agentPortBase;
  private final SettableFuture<Path> androidManifestPath;

  AndroidInstallApkOptions(Map<String, String> options) throws RuntimeException {
    this.restartAdbOnFailure =
        Boolean.parseBoolean(
            options.getOrDefault("adb_restart_on_failure", Boolean.FALSE.toString()));
    this.skipInstallMetadata =
        Boolean.parseBoolean(
            options.getOrDefault("skip_install_metadata", Boolean.FALSE.toString()));
    this.alwayUseJavaAgent =
        Boolean.parseBoolean(
            options.getOrDefault("skip_install_metadata", Boolean.FALSE.toString()));
    this.isZstdCompressionEnabled =
        Boolean.parseBoolean(
            options.getOrDefault("is_zstd_compression_enabled", Boolean.FALSE.toString()));
    this.agentPortBase = Integer.parseInt(options.getOrDefault("agent_port_base", "2828"));
    this.androidManifestPath = SettableFuture.create();
  }

  public void setAndroidManifestPath(Path androidManifestPath) {
    this.androidManifestPath.set(androidManifestPath);
  }

  public Path getAndroidManifestPath() throws InterruptedException, ExecutionException {
    return this.androidManifestPath.get();
  }
}
