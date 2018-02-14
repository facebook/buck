/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.immutables.value.Value;
import org.pf4j.PluginManager;

@Value.Immutable(builder = false, copy = false)
@BuckStyleTuple
interface AbstractDistBuildCellParams {
  BuckConfig getConfig();

  ProjectFilesystem getFilesystem();

  Optional<String> getCanonicalName();

  ImmutableMap<String, String> getEnvironment();

  ProcessExecutor getProcessExecutor();

  ExecutableFinder getExecutableFinder();

  PluginManager getPluginManager();
}
