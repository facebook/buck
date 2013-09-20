/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.plugin.intellij;

import com.facebook.buck.plugin.intellij.commands.BuckRunner;
import com.facebook.buck.plugin.intellij.commands.BuildCommand;
import com.facebook.buck.plugin.intellij.commands.CleanCommand;
import com.facebook.buck.plugin.intellij.commands.SocketClient.BuckPluginEventListener;
import com.facebook.buck.plugin.intellij.commands.TargetsCommand;
import com.facebook.buck.plugin.intellij.commands.event.Event;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.BackgroundFromStartOption;

public class BuckPluginComponent implements ProjectComponent {

  private final Project project;
  private final EventListener listener;
  private Optional<BuckRunner> buckRunner;

  public BuckPluginComponent(Project project) {
    this.project = Preconditions.checkNotNull(project);
    this.listener = new EventListener();
    setBuckDirectory(Optional.<String>absent());
  }

  @Override
  public void projectOpened() {
    if (buckRunner.isPresent()) {
      buckRunner.get().launchBuckd();
    }
  }

  @Override
  public void projectClosed() {
  }

  @Override
  public void initComponent() {
  }

  @Override
  public void disposeComponent() {
  }

  @Override
  public String getComponentName() {
    return "BuckComponent";
  }

  public void setBuckDirectory(Optional<String> buckDirectory) {
    Preconditions.checkNotNull(buckDirectory);
    try {
      buckRunner = Optional.of(new BuckRunner(project, buckDirectory, listener));
    } catch (BuckRunner.BuckNotFound buckNotFound) {
      buckRunner = Optional.absent();
    }
  }

  private BuckRunner getBuckRunner() throws NoBuckRunnerException {
    if (buckRunner.isPresent()) {
      return buckRunner.get();
    }
    throw new NoBuckRunnerException();
  }

  private void reportBuckNotPresent() {
    // TODO(user) Show error message to UI
  }

  public void refreshTargetsList() {
    try {
      final BuckRunner buckRunner = getBuckRunner();
      Task.Backgroundable task = new Task.Backgroundable(project,
          "Retrieving targets",
          true, /* canBeCanceled */
          BackgroundFromStartOption.getInstance()) {
        public void run(ProgressIndicator progressIndicator) {
          ImmutableList<BuckTarget> targets = TargetsCommand.getTargets(buckRunner);
          // TODO(user) Refresh UI to show targets
        }
      };
      task.queue();
    } catch (NoBuckRunnerException e) {
      reportBuckNotPresent();
    }
  }

  public void clean() {
    try {
      final BuckRunner buckRunner = getBuckRunner();
      Task.Backgroundable task = new Task.Backgroundable(project,
          "Cleaning",
          true, /* canBeCanceled */
          BackgroundFromStartOption.getInstance()) {
        public void run(ProgressIndicator progressIndicator) {
          CleanCommand.clean(buckRunner);
          // TODO(user) Clear built targets on UI
        }
      };
      task.queue();
    } catch (NoBuckRunnerException e) {
      reportBuckNotPresent();
    }
  }

  /**
   * Build a specified target in a background thread, showing a indicator in status bar.
   * @param target Specified target to build
   */
  public void buildTarget(final BuckTarget target) {
    try {
      final BuckRunner buckRunner = getBuckRunner();
      Task.Backgroundable task = new Task.Backgroundable(project,
          "Building",
          true, /* canBeCanceled */
          BackgroundFromStartOption.getInstance()) {
        public void run(ProgressIndicator progressIndicator) {
          // TODO (carbokuo) Update UI
          BuildCommand.build(buckRunner, target);
        }
      };
      task.queue();
    } catch (NoBuckRunnerException e) {
      reportBuckNotPresent();
    }
  }

  private class EventListener implements BuckPluginEventListener {

    @Override
    public void onEvent(Event event) {
    }
  }

  private class NoBuckRunnerException extends Exception {
  }
}
