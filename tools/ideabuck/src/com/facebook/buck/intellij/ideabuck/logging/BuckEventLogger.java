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

package com.facebook.buck.intellij.ideabuck.logging;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public class BuckEventLogger implements EventLogger {

  private static class EventLoggerHolder {
    static final BuckEventLogger INSTANCE = new BuckEventLogger();
  }

  public static BuckEventLogger getInstance() {
    return EventLoggerHolder.INSTANCE;
  }

  static final ExtensionPointName<EventLogger> EP_NAME =
      ExtensionPointName.create("intellij.buck.plugin.EventLogger");
  private List<EventLogger> mLoggers;
  private final Executor mExecutor;

  private BuckEventLogger() {
    this(EP_NAME.getExtensionList());
  }

  private BuckEventLogger(List<EventLogger> loggers) {
    this(loggers, action -> ApplicationManager.getApplication().executeOnPooledThread(action));
  }

  @VisibleForTesting
  BuckEventLogger(List<EventLogger> loggers, Executor executor) {
    mLoggers = loggers;
    mExecutor = executor;
  }

  @Override
  public EventLogger withEventType(String eventType) {
    for (EventLogger eventLogger : mLoggers) {
      eventLogger.withEventType(eventType);
    }
    return this;
  }

  @Override
  public EventLogger withEventAction(String eventAction) {
    for (EventLogger eventLogger : mLoggers) {
      eventLogger.withEventAction(eventAction);
    }
    return this;
  }

  @Override
  public EventLogger withProject(Project project) {
    for (EventLogger eventLogger : mLoggers) {
      eventLogger.withProject(project);
    }
    return this;
  }

  @Override
  public EventLogger withFiles(VirtualFile... virtualFiles) {
    for (EventLogger eventLogger : mLoggers) {
      eventLogger.withFiles(virtualFiles);
    }
    return this;
  }

  @Override
  public EventLogger withExtraData(Map<String, String> extraData) {
    for (EventLogger eventLogger : mLoggers) {
      eventLogger.withExtraData(extraData);
    }
    return this;
  }

  @Override
  public void log() {
    mExecutor.execute(() -> mLoggers.forEach(EventLogger::log));
    // don't allow re-use
    mLoggers = Collections.emptyList();
  }
}
