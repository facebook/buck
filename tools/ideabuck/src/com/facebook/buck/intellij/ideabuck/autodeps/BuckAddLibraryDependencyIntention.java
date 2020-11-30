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
import com.facebook.buck.intellij.ideabuck.logging.EventLogger;
import com.facebook.buck.intellij.ideabuck.logging.Keys;
import com.facebook.buck.intellij.ideabuck.notification.BuckNotification;
import com.google.gson.JsonElement;
import com.intellij.notification.Notification;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.event.HyperlinkEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An intention that will attempt to add a dependency edge to both the Buck graph and the IntelliJ
 * module graph, given the dependency is a library.
 */
public class BuckAddLibraryDependencyIntention extends AbstractBuckAddDependencyIntention {
  private static Logger LOGGER = Logger.getInstance(BuckAddLibraryDependencyIntention.class);

  // Fields pertaining to the dependency that should be resolved/imported
  private final Library importLibrary;

  BuckAddLibraryDependencyIntention(
      CommonAddDependencyDataWrapper wrapper,
      Library importLibrary,
      BuckTarget importSourceTarget) {
    super(wrapper);
    this.importSourceTarget = importSourceTarget;
    this.importLibrary = importLibrary;
    // Want to display correct target before running Buck query
    TargetMetadata importMetadata = new TargetMetadata();
    importMetadata.target = importSourceTarget;
    String target =
        TargetMetadataTransformer.transformImportedTarget(project, importMetadata)
            .target
            .toString();
    String message = "Add BUCK dependency on library(" + target + ")";
    setText(message);
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, PsiFile psiFile)
      throws IncorrectOperationException {
    String msg =
        "Invoked for project " + project.getName() + " and library " + importLibrary.getName();
    LOGGER.info(msg);
    super.invoke(project, editor, psiFile);
  }

  /** Queries buck for targets that own the editSourceFile. */
  @Override
  protected void queryBuckForTargets(Editor editor, EventLogger buckEventLogger) {
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    String editPath = editSourceFile.getPath();
    BuckJsonCommandHandler<List<TargetMetadata>> handler =
        new BuckJsonCommandHandler<>(
            project,
            BuckCommand.QUERY,
            new BuckJsonCommandHandler.Callback<List<TargetMetadata>>() {
              @Override
              public List<TargetMetadata> deserialize(JsonElement jsonElement) {
                return parseJson(jsonElement, buckTargetLocator);
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
                }
                TargetMetadata importMetadata = new TargetMetadata();
                importMetadata.target = buckTargetLocator.resolve(importSourceTarget);
                importTargets.add(
                    TargetMetadataTransformer.transformImportedTarget(project, importMetadata));
                updateDependencies(editor, editTargets, importTargets, buckEventLogger);
              }

              @Override
              public void onFailure(
                  String stdout,
                  String stderr,
                  @Nullable Integer exitCode,
                  @Nullable Throwable throwable) {
                String message = "Could not determine owners for " + editSourceFile;
                logFail(message, buckEventLogger);
                BuckNotification.getInstance(project).showWarningBalloon(message);
              }
            });
    handler
        .command()
        .addParameters("owner(%s)", editPath, "--output-attributes=deps|srcs|resources");
    handler.runInCurrentThreadPostEnd(() -> {});
  }

  /**
   * Implementation of {@link
   * com.intellij.notification.NotificationListener#hyperlinkUpdate(Notification, HyperlinkEvent)}.
   */
  @Override
  protected void hyperlinkActivated(
      @NotNull Notification notification, @NotNull HyperlinkEvent event) {
    super.hyperlinkActivated(notification, event);
  }

  private void updateDependencies(
      Editor editor,
      List<TargetMetadata> editTargets,
      List<TargetMetadata> importTargets,
      EventLogger buckEventLogger) {
    TargetMetadata editTargetMetadata =
        getTargetMetaDataFromList(
            editTargets,
            "<html><b>Add dependency failed</b>: Couldn't determine a Buck owner for <a href='editSourceFile'>"
                + editSourceTarget
                + "</a> in <a href='editBuildFile'>"
                + editBuildFile.getPath()
                + "</a>");
    if (editTargetMetadata == null) {
      logFail(
          "Could not determine owner for edit source file " + editSourceTarget, buckEventLogger);
      return;
    }
    // No error message here, since at this point importTargets should have the library as target.
    TargetMetadata importTargetMetadata = getTargetMetaDataFromList(importTargets, "");
    if (importTargetMetadata == null) {
      return;
    }
    editTarget = editTargetMetadata.target;
    importTarget = importTargetMetadata.target;
    if (!tryToAddBuckDependency(editTargetMetadata, buckEventLogger)) {
      return;
    }
    ModuleRootModificationUtil.updateModel(
        editModule,
        (modifiableRootModel -> {
          if (modifiableRootModel.findLibraryOrderEntry(importLibrary) != null) {
            LOGGER.info(
                "No need to modify module "
                    + editModule.getName()
                    + ", already has dependency on "
                    + importLibrary.getName());
          } else {
            modifiableRootModel.addLibraryEntry(importLibrary);
            LOGGER.info(
                "Successfully added module dependency from "
                    + editModule.getName()
                    + " on "
                    + importLibrary.getName());
          }
          buckEventLogger.withExtraData(getExtraLoggingData()).log();
        }));
    invokeAddImport(editor);
  }

  @Override
  Map<String, String> getExtraLoggingData() {
    Map<String, String> data = super.getExtraLoggingData();
    if (importLibrary != null) {
      data.put(Keys.LIBRARY, importLibrary.getName());
    }
    return data;
  }
}
