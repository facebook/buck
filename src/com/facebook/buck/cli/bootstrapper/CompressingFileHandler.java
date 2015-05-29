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

package com.facebook.buck.cli.bootstrapper;

import java.util.logging.Handler;
import java.util.logging.LogRecord;

public class CompressingFileHandler extends Handler {
  private static final Class<?> LOG_CONFIG_CLASS =
      ClassLoaderBootstrapper.loadClass("com.facebook.buck.log.CompressingFileHandler");

  private final Handler handler;

  public CompressingFileHandler() throws IllegalAccessException, InstantiationException {
    handler = (Handler) LOG_CONFIG_CLASS.newInstance();
  }

  @Override
  public void publish(LogRecord record) {
    handler.publish(record);
  }

  @Override
  public void flush() {
    handler.flush();
  }

  @Override
  public void close() throws SecurityException {
    handler.close();
  }
}
