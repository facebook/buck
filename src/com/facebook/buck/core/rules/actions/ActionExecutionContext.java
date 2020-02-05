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

package com.facebook.buck.core.rules.actions;

import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.util.ProcessExecutor;
import java.nio.file.Path;
import java.util.Map;

/**
 * Holds the information {@link Action}s can use for its {@link
 * Action#execute(ActionExecutionContext)}
 */
@BuckStyleValue
public abstract class ActionExecutionContext {
  // TODO(bobyf): fill more as needed. The current is approximately what is needed based on
  // BuildContext and ExecutionContext

  protected abstract BuckEventBus getBuckEventBus();

  /**
   * @return an {@link ArtifactFilesystem} the {@link Action} can access for it's execution. This is
   *     a filesystem that operates on {@link com.facebook.buck.core.artifact.Artifact}s without
   *     requiring actions to be aware of the actual underlying paths.
   */
  public abstract ArtifactFilesystem getArtifactFilesystem();

  /** Logs an error */
  public void logError(Throwable e, String msg, Object... formatArgs) {
    getBuckEventBus().post(ThrowableConsoleEvent.create(e, msg, formatArgs));
  }

  /** posts the given event to the global event bus */
  public void postEvent(BuckEvent event) {
    getBuckEventBus().post(event);
  }

  /** @return The executor to run processes in */
  public abstract ProcessExecutor getProcessExecutor();

  /** @return The environment of the current context */
  public abstract Map<String, String> getEnvironment();

  /** @return The working directory for the current context */
  public abstract Path getWorkingDirectory();

  public static ActionExecutionContext of(
      BuckEventBus buckEventBus,
      ArtifactFilesystem artifactFilesystem,
      ProcessExecutor processExecutor,
      Map<String, ? extends String> environment,
      Path workingDirectory) {
    return ImmutableActionExecutionContext.of(
        buckEventBus, artifactFilesystem, processExecutor, environment, workingDirectory);
  }
}
