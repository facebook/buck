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
import com.facebook.buck.intellij.ideabuck.logging.EventLogger;
import com.facebook.buck.intellij.ideabuck.logging.EventLoggerFactoryProvider;
import com.facebook.buck.intellij.ideabuck.logging.Keys;
import com.facebook.buck.intellij.ideabuck.notification.BuckNotification;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.notification.Notification;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.util.IncorrectOperationException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.event.HyperlinkEvent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Base class to be extended by Intentions that fixes Buck and IntelliJ dependencies. */
public abstract class AbstractBuckAddDependencyIntention extends BaseIntentionAction
    implements PriorityAction {

  final Project project;

  // Fields pertaining to the PsiReference in the file being edited
  final PsiReference reference;
  final VirtualFile editBuildFile;
  final VirtualFile editSourceFile;
  final BuckTarget editSourceTarget;
  final Module editModule;

  // Fields pertaining to the dependency that should be resolved/imported
  @Nullable final PsiClass psiClass;

  // Should be set by subclass
  BuckTarget editTarget;
  BuckTarget importSourceTarget;
  BuckTarget importTarget;

  @Nullable final BuckAddImportAction addImportAction;

  public AbstractBuckAddDependencyIntention(CommonAddDependencyDataWrapper wrapper) {
    project = wrapper.project;
    reference = wrapper.reference;
    editBuildFile = wrapper.editBuildFile;
    editSourceFile = wrapper.editSourceFile;
    editSourceTarget = wrapper.editSourceTarget;
    editModule = wrapper.editModule;
    psiClass = wrapper.psiClass;
    addImportAction = wrapper.addImportAction;
  }

  @Nls(capitalization = Nls.Capitalization.Sentence)
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
    EventLogger buckEventLogger =
        EventLoggerFactoryProvider.getInstance()
            .getBuckEventLogger(Keys.FILE_QUICKFIX)
            .withEventAction(this.getClass().getSimpleName())
            .withProjectFiles(project, editSourceFile);
    // will run buck query in the background, no need to block the ui for that
    ApplicationManager.getApplication()
        .executeOnPooledThread(
            () -> {
              queryBuckForTargets(editor, buckEventLogger);
            });
  }

  abstract void queryBuckForTargets(Editor editor, EventLogger buckEventLogger);

  List<TargetMetadata> parseJson(JsonElement jsonElement, BuckTargetLocator buckTargetLocator) {
    Type type = new TypeToken<Map<String, JsonObject>>() {}.getType();
    Map<String, JsonObject> raw = new Gson().fromJson(jsonElement, type);
    List<TargetMetadata> results = new ArrayList<>();
    for (Map.Entry<String, JsonObject> entry : raw.entrySet()) {
      BuckTarget.parse(entry.getKey())
          .map(buckTargetLocator::resolve)
          .map(target -> TargetMetadata.from(buckTargetLocator, target, entry.getValue()))
          .ifPresent(results::add);
    }
    return results;
  }

  void hyperlinkActivated(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
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
    }
  }

  TargetMetadata getTargetMetaDataFromList(
      List<TargetMetadata> targetMetadataList, String errorMessage) {
    if (targetMetadataList.size() == 0) {
      BuckNotification.getInstance(project)
          .showErrorBalloon(errorMessage, this::hyperlinkActivated);
      return null;
    }
    return targetMetadataList.get(0);
  }

  boolean tryToAddBuckDependency(TargetMetadata editTargetMetadata, EventLogger buckEventLogger) {
    if (!editTargetMetadata.hasDependencyOn(importTarget)) {
      if (!BuckDeps.modifyTargetToAddDependency(
          editBuildFile, editTarget.toString(), importTarget.toString())) {
        String message =
            "<html><b>Add dependency failed</b>:  Could not add modify build file for <a href='editTarget'>"
                + editTarget
                + "</a> to add dependency on <a href='importTarget'>"
                + importTarget
                + "</a></html>";
        logFail(
            "Could not modify build rule for edit target "
                + editTarget
                + " to add dependency on importTarget "
                + importTarget,
            buckEventLogger);
        BuckNotification.getInstance(project).showErrorBalloon(message, this::hyperlinkActivated);
        return false;
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
    return true;
  }

  void invokeAddImport(Editor editor) {
    if (addImportAction == null || !ReadAction.compute(this::isImportedValid)) {
      return;
    }
    DumbService.getInstance(project)
        .smartInvokeLater(
            () -> {
              if (isImportedValid()) {
                addImportAction.execute(project, reference, editor, psiClass);
              }
            });
  }

  boolean isImportedValid() {
    // null means the imported component is not a class (e.g. kotlin extension function)
    return psiClass == null || psiClass.isValid();
  }

  @NotNull
  @Override
  public Priority getPriority() {
    return Priority.TOP;
  }

  void logFail(String message, EventLogger buckEventLogger) {
    Map<String, String> data = getExtraLoggingData();
    String className =
        (psiClass == null || psiClass.getQualifiedName() == null)
            ? "null"
            : psiClass.getQualifiedName();
    Map<String, String> extraData =
        ImmutableMap.<String, String>builder()
            .putAll(data)
            .putAll(ImmutableMap.of(Keys.ERROR, message, Keys.CLASS_NAME, className))
            .build();
    buckEventLogger.withExtraData(extraData).log();
  }

  Map<String, String> getExtraLoggingData() {
    Map<String, String> data = new HashMap<>();
    if (editTarget != null) {
      data.put(Keys.EDIT_TARGET, editTarget.toString());
    }
    if (importTarget != null) {
      data.put(Keys.IMPORT_TARGET, importTarget.toString());
    }
    return data;
  }
}
