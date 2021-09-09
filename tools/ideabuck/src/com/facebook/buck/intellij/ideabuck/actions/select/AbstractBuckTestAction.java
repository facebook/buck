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

package com.facebook.buck.intellij.ideabuck.actions.select;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckJsonCommandHandler;
import com.facebook.buck.intellij.ideabuck.configurations.BuckRunnerAndConfigurationSettingsFactory;
import com.facebook.buck.intellij.ideabuck.configurations.BuckTestConfigurationType;
import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.facebook.buck.intellij.ideabuck.notification.BuckNotification;
import com.google.common.base.Joiner;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.impl.RunManagerImpl;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/** Base action to use buck to run/debug a test in a class/method. */
public abstract class AbstractBuckTestAction extends AnAction {

  /** Use the same truncating strategy as the JUnit method action. */
  protected static String truncateName(String name) {
    if (name.length() >= 21) {
      name = name.substring(0, 18) + "...";
    }
    return name;
  }

  /** If true, use the debugger to run the tests. */
  protected abstract boolean isDebug();

  /** Updates the given presentation based on the given class/method. */
  protected void updatePresentation(
      Presentation presentation, @Nullable PsiClass psiClass, @Nullable PsiMethod psiMethod) {
    String verb;
    if (isDebug()) {
      verb = "Debug";
      presentation.setIcon(BuckIcons.BUCK_DEBUG);
    } else {
      verb = "Run";
      presentation.setIcon(BuckIcons.BUCK_RUN);
    }
    if (psiMethod != null) {
      presentation.setText(verb + " '" + truncateName(psiMethod.getName()) + "()' with Buck");
      presentation.setEnabledAndVisible(true);
    } else if (psiClass != null) {
      presentation.setText(verb + " '" + psiClass.getName() + "' with Buck");
      presentation.setEnabledAndVisible(true);
    } else {
      presentation.setText(verb + " test with Buck");
      presentation.setEnabledAndVisible(false);
    }
  }

  /** Setup and execute a test configuration. */
  protected void setupAndExecuteTestConfiguration(
      PsiClass psiClass, @Nullable PsiMethod psiMethod) {
    Project project = psiClass.getProject();
    VirtualFile virtualFile = psiClass.getContainingFile().getVirtualFile();
    String name;
    String testSelectors;
    if (psiMethod != null) {
      name = psiClass.getName() + "#" + psiMethod.getName();
      testSelectors = psiClass.getQualifiedName() + "#" + psiMethod.getName();
    } else {
      name = psiClass.getName();
      testSelectors = psiClass.getQualifiedName();
    }
    createTestConfigurationFromContext(name, testSelectors, project, virtualFile);
  }

  /**
   * Create a test Configuration and run or debug it in the ide.
   *
   * @param name a {@link String} representing the name of the configuration.
   * @param testSelectors a {@link String} representing optional testSelectors for filtering.
   * @param project {@link Project} then intellij project corresponding the the file or module under
   *     test.
   * @param containingFile {@link VirtualFile} the file that containing the impacted tests.
   */
  protected void createTestConfigurationFromContext(
      String name, String testSelectors, Project project, VirtualFile containingFile) {
    AtomicReference<RunnerAndConfigurationSettings> settingsReference = new AtomicReference<>();
    BuckBuildManager buildManager = BuckBuildManager.getInstance(project);
    String containingFilePath = containingFile.getPath();
    BuckJsonCommandHandler handler =
        new BuckJsonCommandHandler(
            project,
            BuckCommand.QUERY,
            new BuckJsonCommandHandler.Callback<Map<String, List<String>>>() {
              @Override
              public void onSuccess(Map<String, List<String>> result, String stderr) {
                List<String> ownersList = result.get(containingFilePath);
                if (ownersList == null || ownersList.isEmpty()) {
                  BuckNotification.getInstance(project)
                      .showWarningBalloon(
                          "No test targets found that are owners of " + containingFilePath);
                } else {
                  BuckTestConfigurationType type = BuckTestConfigurationType.getInstance();
                  if (type.getConfigurationFactories().length > 0) {
                    RunManagerImpl runManager = (RunManagerImpl) RunManager.getInstance(project);
                    RunnerAndConfigurationSettings runnerAndConfigurationSettings =
                        BuckRunnerAndConfigurationSettingsFactory.getBuckTestConfigSettings(
                            runManager, name, Joiner.on(" ").join(ownersList), "", testSelectors);
                    settingsReference.set(runnerAndConfigurationSettings);
                    runManager.addConfiguration(runnerAndConfigurationSettings, false);
                  }
                }
              }

              @Override
              public void onFailure(
                  String stdout,
                  String stderr,
                  @Nullable Integer exitCode,
                  @Nullable Throwable throwable) {
                BuckNotification.getInstance(project)
                    .showWarningBalloon(
                        "Cannot run test: could not determine owners for " + containingFilePath);
              }
            });

    handler.command().addParameters("owner(%s)", containingFilePath);
    buildManager.runInCurrentThreadPostEnd(
        handler,
        () -> {
          RunnerAndConfigurationSettings settings = settingsReference.getAndSet(null);
          if (settings != null) {
            RunManagerImpl runManager = (RunManagerImpl) RunManager.getInstance(project);
            runManager.setSelectedConfiguration(settings);
            Executor executor;
            if (isDebug()) {
              executor = DefaultDebugExecutor.getDebugExecutorInstance();
            } else {
              if (ExecutorRegistry.getInstance().getRegisteredExecutors().length == 0) {
                return;
              }
              executor = ExecutorRegistry.getInstance().getRegisteredExecutors()[0];
            }
            ProgramRunnerUtil.executeConfiguration(project, settings, executor);
          }
        });
  }
}
