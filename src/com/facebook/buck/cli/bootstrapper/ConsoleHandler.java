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

package com.facebook.buck.cli.bootstrapper;

import java.util.concurrent.Callable;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

public final class ConsoleHandler extends Handler implements Callable<Handler> {
  private static final Class<?> CONSOLE_HANDLER_CLASS =
      ClassLoaderBootstrapper.loadClass("com.facebook.buck.log.ConsoleHandler");

  private final Handler inner;

  public ConsoleHandler() throws IllegalAccessException, InstantiationException {
    inner = (Handler) CONSOLE_HANDLER_CLASS.newInstance();
  }

  @Override
  public void publish(LogRecord record) {
    inner.publish(record);
  }

  @Override
  public void flush() {
    inner.flush();
  }

  @Override
  public void close() throws SecurityException {
    inner.close();
  }

  @Override
  public Handler call() {
    return inner;
  }
}
