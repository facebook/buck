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

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public class BuckEventLogger implements EventLogger {

  private List<EventLogger> mLoggers;
  private final Executor mExecutor;

  public BuckEventLogger(List<EventLogger> loggers) {
    this(loggers, action -> ApplicationManager.getApplication().executeOnPooledThread(action));
  }

  BuckEventLogger(List<EventLogger> loggers, Executor executor) {
    mLoggers = loggers;
    mExecutor = executor;
  }

  @Override
  public EventLogger withEventAction(String eventAction) {
    for (EventLogger eventLogger : mLoggers) {
      eventLogger.withEventAction(eventAction);
    }
    return this;
  }

  @Override
  public EventLogger withProjectFiles(Project project, VirtualFile... virtualFiles) {
    for (EventLogger eventLogger : mLoggers) {
      eventLogger.withProjectFiles(project, virtualFiles);
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
  }
}
