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

package com.facebook.buck.intellij.ideabuck.autodeps;

import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckJsonCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckJsonCommandHandler.Callback;
import com.facebook.buck.intellij.ideabuck.notification.BuckNotification;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.intellij.notification.Notification;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.util.IncorrectOperationException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.swing.event.HyperlinkEvent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nls.Capitalization;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An intention that will attempt to add a dependency edge to both the Buck graph and the IntelliJ
 * module graph.
 */
public class BuckAddDependencyIntention extends AbstractBuckAddDependencyIntention {
  private static Logger LOGGER = Logger.getInstance(BuckAddDependencyIntention.class);

  private final VirtualFile importBuildFile;
  private final VirtualFile importSourceFile;
  private final Module importModule;

  // These methods are here to keep the method signatures the same
  @Nullable
  public static BuckAddDependencyIntention create(PsiReference reference, PsiClass psiClass) {
    return BuckAddDependencyIntentionFactory.createAddModuleDependencyIntention(
        reference, psiClass);
  }

  @Nullable
  public static BuckAddDependencyIntention create(
      PsiReference reference, PsiClass psiClass, BuckAddImportAction importAction) {
    return BuckAddDependencyIntentionFactory.createAddModuleDependencyIntention(
        reference, psiClass, importAction);
  }

  @Nullable
  public static BuckAddDependencyIntention create(
      PsiReference reference,
      VirtualFile importSourceFile,
      @Nullable PsiClass psiClass,
      BuckAddImportAction importAction) {
    return BuckAddDependencyIntentionFactory.createAddModuleDependencyIntention(
        reference, importSourceFile, psiClass, importAction);
  }

  BuckAddDependencyIntention(
      CommonAddDependencyDataWrapper wrapper,
      VirtualFile importSourceFile,
      VirtualFile importBuildFile,
      Module importModule,
      BuckTarget importSourceTarget) {
    super(wrapper);
    this.importBuildFile = importBuildFile;
    this.importSourceFile = importSourceFile;
    this.importSourceTarget = importSourceTarget;
    this.importModule = importModule;
    String message = "Add BUCK dependency on owner(" + importSourceTarget + ")";
    setText(message);
  }

  @Nls(capitalization = Capitalization.Sentence)
  @NotNull
  @Override
  public String getFamilyName() {
    return this.getClass().getSimpleName();
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile psiFile) {
    return true;
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, PsiFile psiFile)
      throws IncorrectOperationException {
    String msg = "Invoked for project " + project.getName() + " and file " + psiFile.getName();
    LOGGER.info(msg);
    // will run buck query in the background, no need to block the ui for that
    ApplicationManager.getApplication()
        .executeOnPooledThread(
            () -> {
              queryBuckForTargets(editor);
            });
  }

  /** Queries buck for targets that own the editSourceFile and the importSourceFile. */
  private void queryBuckForTargets(Editor editor) {
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    String editPath = editSourceFile.getPath();
    String importPath = importSourceFile.getPath();
    BuckJsonCommandHandler<List<TargetMetadata>> handler =
        new BuckJsonCommandHandler<>(
            project,
            BuckCommand.QUERY,
            new Callback<List<TargetMetadata>>() {
              @Override
              public List<TargetMetadata> deserialize(JsonElement jsonElement) {
                Type type = new TypeToken<Map<String, JsonObject>>() {}.getType();
                Map<String, JsonObject> raw = new Gson().fromJson(jsonElement, type);
                List<TargetMetadata> results = new ArrayList<>();
                for (Entry<String, JsonObject> entry : raw.entrySet()) {
                  BuckTarget.parse(entry.getKey())
                      .map(buckTargetLocator::resolve)
                      .map(
                          target ->
                              TargetMetadata.from(buckTargetLocator, target, entry.getValue()))
                      .ifPresent(results::add);
                }
                return results;
              }

              @Override
              public void onSuccess(List<TargetMetadata> results, String stderr) {
                List<TargetMetadata> editTargets = new ArrayList<>();
                List<TargetMetadata> importTargets = new ArrayList<>();
                for (TargetMetadata targetMetadata : results) {
                  if (targetMetadata.contains(editSourceTarget)) {
                    editTargets.add(
                        TargetMetadataTransformer.transformEditedTarget(project, targetMetadata));
                  }
                  if (targetMetadata.contains(importSourceTarget)) {
                    importTargets.add(
                        TargetMetadataTransformer.transformImportedTarget(project, targetMetadata));
                  }
                }
                updateDependencies(editor, editTargets, importTargets);
              }

              @Override
              public void onFailure(
                  String stdout,
                  String stderr,
                  @Nullable Integer exitCode,
                  @Nullable Throwable throwable) {
                BuckNotification.getInstance(project)
                    .showWarningBalloon(
                        "Could not determine owners for "
                            + editSourceFile
                            + " and/or "
                            + importSourceFile);
              }
            });
    handler
        .command()
        .addParameters(
            "owner(%s)",
            editPath, importPath, "--output-attributes=deps|srcs|visibility|resources");
    handler.runInCurrentThreadPostEnd(() -> {});
  }

  /**
   * Implementation of {@link
   * com.intellij.notification.NotificationListener#hyperlinkUpdate(Notification, HyperlinkEvent)}.
   */
  private void hyperlinkActivated(
      @NotNull Notification notification, @NotNull HyperlinkEvent event) {
    String href = event.getDescription();
    switch (href) {
      case "editTarget":
        if (BuckTargetLocator.getInstance(project)
            .findElementForTarget(editTarget)
            .filter(target -> target instanceof NavigatablePsiElement)
            .map(target -> (NavigatablePsiElement) target)
            .filter(Navigatable::canNavigate)
            .map(
                e -> {
                  e.navigate(true);
                  return true;
                })
            .orElse(false)) {
          break;
        }
        // fallthrough
      case "editBuildFile":
        FileEditorManager.getInstance(project).openFile(editBuildFile, true);
        break;
      case "editSourceFile":
        FileEditorManager.getInstance(project).openFile(editSourceFile, true);
        break;
      case "importTarget":
        if (BuckTargetLocator.getInstance(project)
            .findElementForTarget(importTarget)
            .filter(target -> target instanceof NavigatablePsiElement)
            .map(target -> (NavigatablePsiElement) target)
            .filter(Navigatable::canNavigate)
            .map(
                e -> {
                  e.navigate(true);
                  return true;
                })
            .orElse(false)) {
          break;
        }
        // fallthrough
      case "importBuildFile":
        FileEditorManager.getInstance(project).openFile(importBuildFile, true);
        break;
      case "importSourceFile":
        FileEditorManager.getInstance(project).openFile(importSourceFile, true);
        break;
    }
  }

  private void updateDependencies(
      Editor editor, List<TargetMetadata> editTargets, List<TargetMetadata> importTargets) {
    if (editTargets.size() == 0) {
      String message =
          "<html><b>Add dependency failed</b>: Couldn't determine a Buck owner for <a href='editSourceFile'>"
              + editSourceTarget
              + "</a> in <a href='editBuildFile'>"
              + editBuildFile.getPath()
              + "</a>";
      BuckNotification.getInstance(project).showErrorBalloon(message, this::hyperlinkActivated);
      return;
    }
    if (importTargets.size() == 0) {
      String message =
          "<html><b>Add dependency failed</b>: Couldn't determine a Buck owner for <a href='importSourceFile'>"
              + importSourceTarget
              + "</a> in <a href='importBuildFile'>"
              + importBuildFile.getPath()
              + "</a></html>";
      BuckNotification.getInstance(project).showErrorBalloon(message, this::hyperlinkActivated);
      return;
    }
    TargetMetadata editTargetMetadata = editTargets.get(0);
    TargetMetadata importTargetMetadata = importTargets.get(0);
    editTarget = editTargetMetadata.target;
    importTarget = importTargetMetadata.target;

    if (!importTargetMetadata.isVisibleTo(editTarget)) {
      String message =
          "<html><b>Add dependency failed</b>: The target <a href='importTarget'>"
              + importTarget
              + "</a> is not visible to <a href='editTarget'>"
              + editTarget
              + "</a></html>";
      BuckNotification.getInstance(project).showErrorBalloon(message, this::hyperlinkActivated);
      return;
    }
    if (!editTargetMetadata.hasDependencyOn(importTarget)) {
      if (!BuckDeps.modifyTargetToAddDependency(
          editBuildFile, editTarget.toString(), importTarget.toString())) {
        String message =
            "<html><b>Add dependency failed</b>:  Could not add modify build file for <a href='editTarget'>"
                + editTarget
                + "</a> to add dependency on <a href='importTarget'>"
                + importTarget
                + "</a></html>";
        BuckNotification.getInstance(project).showErrorBalloon(message, this::hyperlinkActivated);
        return;
      }
    } else {
      String message =
          "<html>No need to modify build file <a href='editBuildFile'>"
              + editBuildFile
              + "</a>, already has dependency from <a href='editTarget'>"
              + editTarget
              + "</a> to <a href='importTarget'>"
              + importTarget
              + "</a></html>";
      BuckNotification.getInstance(project).showInfoBalloon(message, this::hyperlinkActivated);
    }
    ModuleRootModificationUtil.updateModel(
        editModule,
        (modifiableRootModel -> {
          if (modifiableRootModel.findModuleOrderEntry(importModule) != null) {
            LOGGER.info(
                "No need to modify module "
                    + editModule.getName()
                    + ", already has dependency on "
                    + importModule.getName());
          } else {
            modifiableRootModel.addModuleOrderEntry(importModule);
            LOGGER.info(
                "Successfully added module dependency from "
                    + editModule.getName()
                    + " on "
                    + importModule.getName());
          }
        }));
    if (addImportAction == null) {
      return;
    }
    // Add import will run main thread since it's a write action and will block the IDE
    DumbService.getInstance(project)
        .smartInvokeLater(
            () -> {
              if (isImportedValid()) {
                addImportAction.execute(project, reference, editor, psiClass);
              }
            });
  }

  private boolean isImportedValid() {
    // null means the imported component is not a class (e.g. kotlin extension function)
    return psiClass == null || psiClass.isValid();
  }
}
