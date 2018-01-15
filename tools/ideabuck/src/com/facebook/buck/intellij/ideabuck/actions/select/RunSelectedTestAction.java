/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.intellij.ideabuck.actions.select;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckQueryCommandHandler;
import com.facebook.buck.intellij.ideabuck.configurations.TestConfiguration;
import com.facebook.buck.intellij.ideabuck.configurations.TestConfigurationType;
import com.facebook.buck.intellij.ideabuck.file.BuckFileUtil;
import com.google.common.base.Function;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.impl.RunManagerImpl;
import com.intellij.execution.impl.RunnerAndConfigurationSettingsImpl;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.swing.Icon;

/**
 * Abstract class collecting common logic for running and debugging buck tests from the editor. This
 * includes logic to handle creating, running and debugging test configurations in the intellij ide.
 */
public class RunSelectedTestAction extends AnAction {

  private RunnerAndConfigurationSettings runnerAndConfigurationSettingsResult;
  private PsiElement element;
  private boolean debug;

  RunSelectedTestAction(
      @Nullable String text,
      @Nullable String description,
      @Nullable Icon icon,
      boolean debug,
      PsiElement psiElement) {
    super(text, description, icon);
    this.debug = debug;
    this.element = psiElement;
  }

  @Override
  public void actionPerformed(AnActionEvent anActionEvent) {
    setupAndExecuteTestConfigurationFromEditor(anActionEvent);
  }

  /**
   * Setup and execute a test configuration.
   *
   * @param event the event corresponding to an action.
   */
  void setupAndExecuteTestConfigurationFromEditor(AnActionEvent event) {
    PsiMethod method = null;
    PsiClass psiClass = null;
    if (element instanceof PsiMethod) {
      method = (PsiMethod) element;
      psiClass = method.getContainingClass();
    } else if (element instanceof PsiClass) {
      psiClass = (PsiClass) element;
    } else {
      return;
    }
    final Project project = event.getProject();
    if (project == null) {
      return;
    }
    Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
    if (editor == null) {
      return;
    }
    final Document document = editor.getDocument();
    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);

    VirtualFile buckFile = BuckFileUtil.getBuckFile(virtualFile);
    if (buckFile == null) {
      return;
    }

    String name;
    Optional<String> testSelectors = Optional.empty();
    if (method != null) {
      name = psiClass.getName() + "#" + method.getName();
      testSelectors = Optional.of(psiClass.getName() + "#" + method.getName());
    } else {
      name = element.getContainingFile().getName();
      if (element instanceof PsiClass) {
        testSelectors = Optional.of(psiClass.getQualifiedName());
      }
    }
    createTestConfigurationFromContext(name, testSelectors, project, virtualFile, buckFile, debug);
  }

  /**
   * Create a test Configuration and run or debug it in the ide.
   *
   * @param name a {@link String} representing the name of the configuration.
   * @param testSelectors a {@link String} representing optional testSelectors for filtering.
   * @param project {@link Project} then intellij project corresponding the the file or module under
   *     test.
   * @param containingFile {@link VirtualFile} the file that containing the impacted tests.
   * @param buckFile {@link VirtualFile} the file representing the buck File.
   * @param debug a {@link boolean} used to signify run or debug.
   */
  private void createTestConfigurationFromContext(
      @Nonnull String name,
      final Optional<String> testSelectors,
      final Project project,
      VirtualFile containingFile,
      VirtualFile buckFile,
      boolean debug) {
    BuckBuildManager buildManager = BuckBuildManager.getInstance(project);
    BuckQueryCommandHandler handler =
        new BuckQueryCommandHandler(
            project,
            buckFile.getParent(),
            BuckCommand.QUERY,
            new Function<List<String>, Void>() {
              @Nullable
              @Override
              public Void apply(@Nullable List<String> strings) {
                if (strings == null
                    || strings.isEmpty()
                    || strings.get(0) == null
                    || strings.get(0).isEmpty()) {
                  return null;
                }
                TestConfigurationType type = new TestConfigurationType();
                if (type.getConfigurationFactories().length == 0) {
                  return null;
                }
                RunManagerImpl runManager = (RunManagerImpl) RunManager.getInstance(project);
                RunnerAndConfigurationSettingsImpl runnerAndConfigurationSettings =
                    (RunnerAndConfigurationSettingsImpl)
                        runManager.createRunConfiguration(
                            name, type.getConfigurationFactories()[0]);
                TestConfiguration testConfiguration =
                    (TestConfiguration) runnerAndConfigurationSettings.getConfiguration();
                testConfiguration.data.target = strings.get(0);
                testConfiguration.data.testSelectors = testSelectors.orElse("");
                runnerAndConfigurationSettingsResult = runnerAndConfigurationSettings;
                runManager.addConfiguration(runnerAndConfigurationSettings, false);
                return null;
              }
            });
    handler.command().addParameter("owner(" + containingFile.getPath() + ")");
    buildManager.runInCurrentThreadPostEnd(
        handler,
        () -> {
          if (runnerAndConfigurationSettingsResult != null) {
            RunManagerImpl runManager = (RunManagerImpl) RunManager.getInstance(project);
            runManager.setSelectedConfiguration(runnerAndConfigurationSettingsResult);
            Executor executor;
            if (debug) {
              executor = DefaultDebugExecutor.getDebugExecutorInstance();
            } else {
              if (ExecutorRegistry.getInstance().getRegisteredExecutors().length == 0) {
                return;
              }
              executor = ExecutorRegistry.getInstance().getRegisteredExecutors()[0];
            }
            ProgramRunnerUtil.executeConfiguration(
                project, runnerAndConfigurationSettingsResult, executor);
          }
        });
  }
}
