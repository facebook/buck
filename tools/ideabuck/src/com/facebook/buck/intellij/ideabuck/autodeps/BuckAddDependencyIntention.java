/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.autodeps;

import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckJsonCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckJsonCommandHandler.Callback;
import com.facebook.buck.intellij.ideabuck.notification.BuckNotification;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.intellij.codeInsight.daemon.impl.actions.AddImportAction;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.notification.Notification;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.util.IncorrectOperationException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.swing.event.HyperlinkEvent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nls.Capitalization;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An intention that will attempt to add a dependency edge to both the Buck graph and the IntelliJ
 * module graph.
 */
public class BuckAddDependencyIntention extends BaseIntentionAction {
  private static Logger LOGGER = Logger.getInstance(BuckAddDependencyIntention.class);

  /**
   * Creates an {@link com.intellij.codeInsight.intention.IntentionAction} that will create an
   * dependency edge in both the Buck target graph and IntelliJ module graph from the nodes for the
   * given reference element to those of the given psiClass.
   *
   * <p>Note that this intention can fail to be created if either side of the edge cannot be
   * resolved to a buck file in a buck cell, in which case this method returns null. Also, invoking
   * this intention may fail to create edges in either the Buck target graph or the IntelliJ module
   * graph (or both).
   */
  @Nullable
  public static BuckAddDependencyIntention create(
      PsiJavaCodeReferenceElement referenceElement, PsiClass psiClass) {
    VirtualFile editSourceFile = referenceElement.getContainingFile().getVirtualFile();
    if (editSourceFile == null) {
      return null;
    }
    Project project = referenceElement.getProject();
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    VirtualFile editBuildFile =
        buckTargetLocator.findBuckFileForVirtualFile(editSourceFile).orElse(null);
    if (editBuildFile == null) {
      return null;
    }
    VirtualFile importSourceFile = psiClass.getContainingFile().getVirtualFile();
    if (importSourceFile == null) {
      return null;
    }
    VirtualFile importBuildFile =
        buckTargetLocator.findBuckFileForVirtualFile(importSourceFile).orElse(null);
    if (importBuildFile == null) {
      return null;
    }
    if (importBuildFile.equals(editBuildFile)) {
      return null;
    }
    ProjectFileIndex projectFileIndex = ProjectFileIndex.getInstance(project);
    Module editModule = projectFileIndex.getModuleForFile(editSourceFile);
    if (editModule == null) {
      return null;
    }
    Module importModule = projectFileIndex.getModuleForFile(importSourceFile);
    if (importModule == null) {
      return null;
    }
    BuckTarget editSourceTarget =
        buckTargetLocator
            .findTargetPatternForVirtualFile(editSourceFile)
            .flatMap(BuckTargetPattern::asBuckTarget)
            .orElse(null);
    if (editSourceTarget == null) {
      return null;
    }
    BuckTarget importSourceTarget =
        buckTargetLocator
            .findTargetPatternForVirtualFile(importSourceFile)
            .flatMap(BuckTargetPattern::asBuckTarget)
            .orElse(null);
    if (importSourceTarget == null) {
      return null;
    }
    return new BuckAddDependencyIntention(
        project,
        referenceElement,
        editBuildFile,
        editSourceFile,
        editSourceTarget,
        editModule,
        psiClass,
        importBuildFile,
        importSourceFile,
        importSourceTarget,
        importModule);
  }

  private Project project;

  // Fields pertaining to the element in the file being edited
  private PsiJavaCodeReferenceElement referenceElement;
  private VirtualFile editBuildFile;
  private VirtualFile editSourceFile;
  private BuckTarget editSourceTarget;
  private BuckTarget editTarget;
  private Module editModule;

  // Fields pertaining to the dependency that should be resolved/imported
  private PsiClass psiClass;
  private VirtualFile importBuildFile;
  private VirtualFile importSourceFile;
  private BuckTarget importSourceTarget;
  private BuckTarget importTarget;
  private Module importModule;

  BuckAddDependencyIntention(
      Project project,
      PsiJavaCodeReferenceElement referenceElement,
      VirtualFile editBuildFile,
      VirtualFile editSourceFile,
      BuckTarget editSourceTarget,
      Module editModule,
      PsiClass psiClass,
      VirtualFile importBuildFile,
      VirtualFile importSourceFile,
      BuckTarget importSourceTarget,
      Module importModule) {
    this.project = project;
    this.referenceElement = referenceElement;
    this.editBuildFile = editBuildFile;
    this.editSourceFile = editSourceFile;
    this.editSourceTarget = editSourceTarget;
    this.editModule = editModule;
    this.psiClass = psiClass;
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
    queryBuckForTargets(editor);
  }

  /** Helper class to handle deserialization of buck query. */
  static class TargetMetadata {
    public BuckTarget target;
    public @Nullable List<BuckTarget> deps;
    public @Nullable List<BuckTargetPattern> visibility; // null means PUBLIC
    public @Nullable List<String> srcs;
    public @Nullable List<String> resources;

    static TargetMetadata from(
        BuckTargetLocator buckTargetLocator, BuckTarget target, JsonObject payload) {
      TargetMetadata targetMetadata = new TargetMetadata();
      targetMetadata.target = target;
      targetMetadata.srcs = stringListOrNull(payload.get("srcs"));
      targetMetadata.resources = stringListOrNull(payload.get("resources"));

      // Deps are a list of BuckTargets
      targetMetadata.deps =
          Optional.ofNullable(stringListOrNull(payload.get("deps")))
              .map(
                  deps ->
                      deps.stream()
                          .map(
                              s -> BuckTarget.parse(s).map(buckTargetLocator::resolve).orElse(null))
                          .collect(Collectors.toList()))
              .orElse(null);

      // Visibilility falls in one of three cases:
      //   (1) if unspecified => means visibility is limited to the current package
      //   (2) contains "PUBLIC" => available everywhere
      //   (3) otherwise is a list of buck target patterns where it is visible
      List<String> optionalVisibility = stringListOrNull(payload.get("visibility"));
      if (optionalVisibility == null) {
        targetMetadata.visibility =
            Collections.singletonList(target.asPattern().asPackageMatchingPattern());
      } else if (optionalVisibility.contains("PUBLIC")) {
        targetMetadata.visibility = null; //
      } else {
        targetMetadata.visibility =
            optionalVisibility.stream()
                .map(p -> BuckTargetPattern.parse(p).map(buckTargetLocator::resolve).orElse(null))
                .collect(Collectors.toList());
      }

      return targetMetadata;
    }

    static @Nullable List<String> stringListOrNull(@Nullable JsonElement jsonElement) {
      if (jsonElement == null) {
        return null;
      }
      return new Gson().fromJson(jsonElement, new TypeToken<List<String>>() {}.getType());
    }

    boolean isVisibleTo(BuckTarget target) {
      if (visibility == null) {
        return true;
      }
      return visibility.stream().anyMatch(pattern -> pattern.matches(target));
    }

    boolean hasDependencyOn(BuckTarget target) {
      if (deps == null) {
        return false;
      } else {
        return deps.stream().anyMatch(dep -> dep.equals(target));
      }
    }

    boolean contains(BuckTarget targetFile) {
      if (!target.asPattern().asPackageMatchingPattern().matches(targetFile)) {
        return false;
      }
      String relativeToBuildFile = targetFile.getRuleName();
      return (srcs != null && srcs.contains(relativeToBuildFile))
          || (resources != null && resources.contains(relativeToBuildFile));
    }
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
                    editTargets.add(targetMetadata);
                  }
                  if (targetMetadata.contains(importSourceTarget)) {
                    importTargets.add(targetMetadata);
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
          new AddImportAction(project, referenceElement, editor, psiClass).execute();
        }));
  }
}
