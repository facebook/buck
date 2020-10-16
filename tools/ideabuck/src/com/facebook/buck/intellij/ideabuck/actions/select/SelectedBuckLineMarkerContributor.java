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

import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.facebook.buck.intellij.ideabuck.configurations.BuckRunnerAndConfigurationSettingsFactory;
import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckDotTrailer;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionTrailer;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckIdentifier;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckStatement;
import com.google.common.collect.ImmutableList;
import com.intellij.execution.Executor;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Line markers for a Buck file, with actions available for appropriate Buck targets. */
public class SelectedBuckLineMarkerContributor extends RunLineMarkerContributor {

  private static List<String> RUNNABLE_TARGET_TYPES =
      ImmutableList.of("cxx_binary", "java_binary", "sh_binary");

  @Nullable
  @Override
  public Info getInfo(@NotNull PsiElement psiElement) {
    if (psiElement instanceof LeafPsiElement
        && psiElement.getParent() instanceof BuckIdentifier
        && PsiTreeUtil.getParentOfType(psiElement, BuckArgument.class) == null
        && PsiTreeUtil.getParentOfType(psiElement, BuckDotTrailer.class) == null) {
      BuckStatement buckStatement = PsiTreeUtil.getParentOfType(psiElement, BuckStatement.class);
      if (buckStatement != null) {
        return createInfo(
            psiElement.getProject(), psiElement.getText(), getBuckTarget(buckStatement));
      }
    }
    return null;
  }

  private static String getBuckTargetName(@NotNull BuckStatement buckStatement) {
    return Optional.ofNullable(
            PsiTreeUtil.findChildOfType(buckStatement, BuckFunctionTrailer.class))
        .map(BuckFunctionTrailer::getName)
        .orElse(null);
  }

  @Nullable
  private static BuckTarget getBuckTarget(@NotNull BuckStatement buckStatement) {
    String targetName = getBuckTargetName(buckStatement);
    if (targetName == null) {
      return null;
    }
    VirtualFile buckFile = buckStatement.getContainingFile().getVirtualFile();
    return BuckTargetLocator.getInstance(buckStatement.getProject())
        .findTargetPatternForVirtualFile(buckFile)
        .flatMap(targetPath -> BuckTarget.parse(targetPath + targetName))
        .orElse(null);
  }

  private Info createInfo(Project project, String targetType, @Nullable BuckTarget target) {
    if (target == null) {
      return null;
    }
    List<AnAction> actions = new ArrayList<>();
    targetType =
        BuckProjectSettingsProvider.getInstance(project).getConvertedTargetType(targetType);
    actions.add(new FixedBuckRunAction(BuckCommand.BUILD.name(), target));
    if (isTestTargetType(targetType)) {
      actions.add(new FixedBuckRunAction(BuckCommand.TEST.name(), target));
    }
    if (isRunnableTargetType(targetType)) {
      actions.add(new FixedBuckRunAction(BuckCommand.RUN.name(), target, true));
    }
    return new Info(
        BuckIcons.BUILD_RUN_TEST, actions.toArray(new AnAction[0]), it -> "Build/Test this target");
  }

  private static boolean isTestTargetType(String targetType) {
    return targetType.endsWith("test");
  }

  private static boolean isRunnableTargetType(String targetType) {
    return RUNNABLE_TARGET_TYPES.contains(targetType);
  }

  private static class FixedBuckRunAction extends AnAction {

    private final String commandType;
    private final BuckTarget target;
    private final boolean shouldGetAdditionalParams;

    FixedBuckRunAction(String commandType, BuckTarget target) {
      this(commandType, target, false);
    }

    FixedBuckRunAction(String commandType, BuckTarget target, boolean shouldGetAdditionalParams) {
      this.commandType = commandType;
      this.target = target;
      this.shouldGetAdditionalParams = shouldGetAdditionalParams;
    }

    @Override
    public void update(AnActionEvent e) {
      e.getPresentation().setText(StringUtil.capitalize(commandType) + " This Target With Buck");
      e.getPresentation().setEnabled(true);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      Project project = e.getProject();
      if (project == null) {
        return;
      }
      String params = shouldGetAdditionalParams ? getAdditionalParams(project) : "";
      if (params == null) {
        return;
      }
      RunManager runManager = RunManager.getInstance(project);
      RunnerAndConfigurationSettings settings;
      if (commandType.equals(BuckCommand.BUILD.name())) {
        settings =
            BuckRunnerAndConfigurationSettingsFactory.getBuckBuildConfigSettings(
                runManager, target.toString(), params);
      } else if (commandType.equals(BuckCommand.RUN.name())) {
        settings =
            BuckRunnerAndConfigurationSettingsFactory.getBuckRunConfigSettings(
                runManager, target.toString(), params);
      } else if (commandType.equals(BuckCommand.TEST.name())) {
        settings =
            BuckRunnerAndConfigurationSettingsFactory.getBuckTestConfigSettings(
                runManager, target.toString(), params, "");
      } else {
        return;
      }
      runManager.addConfiguration(settings, false);
      runManager.setSelectedConfiguration(settings);
      Executor executor = DefaultRunExecutor.getRunExecutorInstance();
      ProgramRunnerUtil.executeConfiguration(settings, executor);
    }

    private String getAdditionalParams(Project project) {
      return Messages.showInputDialog(
          project,
          "Input extra parameters for your Buck run command",
          "Additional Params",
          IconLoader.getIcon("/icons/buck_icon.png"),
          "",
          null);
    }
  }
}
