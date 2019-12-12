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

package com.facebook.buck.cli;

import com.android.ddmlib.Log;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.util.Scope;
import java.util.logging.Level;

/**
 * This is used to convert ddmlib's logging to ConsoleEvents to interact correctly with
 * SuperConsole.
 */
public class DdmLibLogRedirector {
  private static Level convertDdmLevel(Log.LogLevel ddmLevel) {
    switch (ddmLevel) {
      case VERBOSE:
        return Level.FINER;
      case DEBUG:
        return Level.FINE;
      case INFO:
        return Level.INFO;
      case WARN:
        return Level.WARNING;
      case ERROR:
      case ASSERT:
      default:
        return Level.SEVERE;
    }
  }

  static Scope redirectDdmLogger(BuckEventBus eventBus) {
    Log.ILogOutput logOutput =
        new Log.ILogOutput() {
          @Override
          public void printLog(Log.LogLevel logLevel, String tag, String message) {
            eventBus.post(ConsoleEvent.create(convertDdmLevel(logLevel), "%s: %s", tag, message));
          }

          @Override
          public void printAndPromptLog(Log.LogLevel logLevel, String tag, String message) {
            printLog(logLevel, tag, message);
          }
        };
    Log.addLogger(logOutput);
    return () -> Log.removeLogger(logOutput);
  }
}
