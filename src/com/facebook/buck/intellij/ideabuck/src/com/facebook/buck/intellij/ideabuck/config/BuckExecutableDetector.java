/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.config;

import com.facebook.buck.intellij.ideabuck.util.ExecutableFinder;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public final class BuckExecutableDetector {
  private static final Path BUCK_EXECUTABLE = Paths.get("buck");
  private static final Path ADB_EXECUTABLE = Paths.get("adb");
  private static final ExecutableFinder EXECUTABLE_FINDER = new ExecutableFinder();

  private Map<String, String> env;

  public BuckExecutableDetector() {
    this(ImmutableMap.copyOf(System.getenv()));
  }

  public BuckExecutableDetector(ImmutableMap<String, String> env) {
    this.env = env;
  }

  public String getBuckExecutable() {
    return getExecutable(BUCK_EXECUTABLE, env);
  }

  public String getAdbExecutable() {
    Map<String, String> modifiedEnv = new HashMap<>(env);
    appendAndroidSdkPlatformToolsAtEndOfPath(modifiedEnv);
    return EXECUTABLE_FINDER
        .getExecutable(ADB_EXECUTABLE, ImmutableMap.copyOf(modifiedEnv))
        .toString();
  }

  private void appendAndroidSdkPlatformToolsAtEndOfPath(Map<String, String> env) {
    String androidSdk = env.get("ANDROID_SDK");
    if (androidSdk == null) {
      return;
    }
    Path androidAdkPlatformTools = Paths.get(androidSdk).resolve("platform-tools");
    String path = env.get("PATH");
    if (path == null) {
      path = "";
    } else {
      path = path + File.pathSeparator;
    }
    path += androidAdkPlatformTools.toAbsolutePath().toString();
    env.put("PATH", path);
  }

  public String getExecutable(Path suggestedExecutable, Map<String, String> env) {
    return EXECUTABLE_FINDER
        .getExecutable(suggestedExecutable, ImmutableMap.copyOf(env))
        .toString();
  }
}
