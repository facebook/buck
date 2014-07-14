/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.log;

import com.google.common.eventbus.Subscribe;

import java.io.IOException;

import java.nio.file.Path;
import java.nio.file.WatchEvent;

/**
 * Processes changes to logging configuration files and reconfigures
 * logging if they change.
 */
public class LogConfigFilesWatcher {
  private static final Logger LOG = Logger.get(LogConfigFilesWatcher.class);

  /**
   * Called whenever a path changes. Reconfigures logging if the changed path
   * is a log configuration path.
   */
  @Subscribe
  public void handlePathChange(WatchEvent<?> event) throws IOException {
    Path path = (Path) event.context();
    if (LogConfigPaths.ALL_PATHS.contains(path)) {
      LOG.debug("Configuration file at path [%s] changed, reloading log config...", path);
      LogConfig.setupLogging();
    }
  }
}
