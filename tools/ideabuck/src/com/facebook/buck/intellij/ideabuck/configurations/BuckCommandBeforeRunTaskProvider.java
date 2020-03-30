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

package com.facebook.buck.intellij.ideabuck.configurations;

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.facebook.buck.intellij.ideabuck.config.BuckExecutableDetector;
import com.facebook.buck.intellij.ideabuck.config.BuckExecutableSettingsProvider;
import com.intellij.execution.BeforeRunTaskProvider;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.tools.Tool;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

/** Provider of {@link BuckCommandBeforeRunTask} objects. */
public class BuckCommandBeforeRunTaskProvider
    extends BeforeRunTaskProvider<BuckCommandBeforeRunTask> {

  private static final Logger LOGGER = Logger.getInstance(BuckCommandBeforeRunTaskProvider.class);

  public static final Key<BuckCommandBeforeRunTask> ID = Key.create("BuckCommand");

  private final Project myProject;

  public BuckCommandBeforeRunTaskProvider(Project project) {
    myProject = project;
  }

  @Override
  public Key<BuckCommandBeforeRunTask> getId() {
    return ID;
  }

  @Override
  public String getName() {
    return "Run buck command";
  }

  @Override
  public boolean isConfigurable() {
    return true;
  }

  private Optional<Path> getBuckExecutable() {
    return Optional.of(myProject)
        .map(BuckExecutableSettingsProvider::getInstance)
        .map(BuckExecutableSettingsProvider::resolveBuckExecutable)
        .map(Paths::get);
  }

  private Optional<Path> getDefaultCellPath() {
    return Optional.of(myProject)
        .map(BuckCellManager::getInstance)
        .flatMap(BuckCellManager::getDefaultCell)
        .map(BuckCellManager.Cell::getRootPath);
  }

  @Override
  public boolean canExecuteTask(
      @NotNull RunConfiguration configuration, @NotNull BuckCommandBeforeRunTask task) {
    return getBuckExecutable().isPresent();
  }

  @Override
  public String getDescription(BuckCommandBeforeRunTask task) {
    String exec = getBuckExecutable().map(p -> p.getFileName().toString()).orElse("buck");
    return exec + " " + String.join(" ", task.getArguments());
  }

  @Nullable
  @Override
  public BuckCommandBeforeRunTask createTask(@NotNull RunConfiguration runConfiguration) {
    return new BuckCommandBeforeRunTask();
  }

  @Override
  public Promise<Boolean> configureTask(
      @NotNull DataContext context,
      @NotNull RunConfiguration configuration,
      @NotNull BuckCommandBeforeRunTask task) {
    Project project = configuration.getProject();
    final BuckCommandToolEditorDialog dialog = new BuckCommandToolEditorDialog(project);
    dialog.setArguments(task.getArguments());
    if (dialog.showAndGet()) {
      task.setArguments(dialog.getArguments());
      Promises.resolvedPromise(true);
    }
    return Promises.resolvedPromise(false);
  }

  @Override
  public boolean executeTask(
      @NotNull DataContext dataContext,
      @NotNull RunConfiguration runConfiguration,
      @NotNull ExecutionEnvironment executionEnvironment,
      @NotNull BuckCommandBeforeRunTask task) {
    Semaphore commandFinished = new Semaphore(0);
    AtomicBoolean commandFinishedSuccessfully = new AtomicBoolean(false);

    // Force output to use console
    Tool tool = createToolForTask(task);
    ApplicationManager.getApplication()
        .invokeLater(
            () ->
                tool.execute(
                    null,
                    dataContext,
                    0,
                    new ProcessListener() {
                      @Override
                      public void startNotified(@NotNull ProcessEvent processEvent) {}

                      @Override
                      public void processTerminated(@NotNull ProcessEvent processEvent) {
                        commandFinishedSuccessfully.set(processEvent.getExitCode() == 0);
                        commandFinished.release();
                      }

                      @Override
                      public void processWillTerminate(
                          @NotNull ProcessEvent processEvent, boolean b) {}

                      @Override
                      public void onTextAvailable(
                          @NotNull ProcessEvent processEvent, @NotNull Key key) {}
                    }));

    try {
      commandFinished.acquire();
    } catch (InterruptedException e) {
      // commandFinishedSuccessfully most likely still false
    }
    return commandFinishedSuccessfully.get();
  }

  @Nonnull
  private Tool createToolForTask(BuckCommandBeforeRunTask task) {
    // Using copyFrom() as a hacky workaround for the visibility issue
    // that Tool doesn't expose the setters setName() or setUseConsole().
    Tool tool =
        new Tool() {
          @Override
          public boolean isUseConsole() {
            return true;
          }

          @Override
          public String getName() {
            return "buck " + String.join(" ", task.getArguments());
          }
        };
    tool.copyFrom(tool);
    getDefaultCellPath().ifPresent(path -> tool.setWorkingDirectory(path.toString()));
    tool.setProgram(
        getBuckExecutable().map(Path::toString).orElse(BuckExecutableDetector.DEFAULT_BUCK_NAME));
    tool.setParameters(String.join(" ", task.getArguments()));
    return tool;
  }
}
