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

package com.facebook.buck.intellij.ideabuck.format;

import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.facebook.buck.intellij.ideabuck.lang.BuckFileType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.UndoConfirmationPolicy;
import com.intellij.openapi.components.BaseComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

/** Reformats build files using {@code buildifier} before saving. */
public class BuildifierAutoFormatter implements BaseComponent, Disposable {

  private static final Logger LOGGER = Logger.getInstance(BuildifierAutoFormatter.class);

  private MessageBusConnection mMessageBusConnection;

  @Override
  public void initComponent() {
    mMessageBusConnection = ApplicationManager.getApplication().getMessageBus().connect(this);
    mMessageBusConnection.subscribe(
        FileEditorManagerListener.FILE_EDITOR_MANAGER,
        new FileEditorManagerListener() {
          @Override
          public void selectionChanged(@NotNull FileEditorManagerEvent event) {
            Project project = event.getManager().getProject();
            if (!BuckProjectSettingsProvider.getInstance(project).isAutoFormatOnBlur()) {
              return;
            }
            FileEditor newFileEditor = event.getNewEditor();
            FileEditor oldFileEditor = event.getOldEditor();
            if (oldFileEditor == null || oldFileEditor.equals(newFileEditor)) {
              return; // still editing same file
            }
            VirtualFile virtualFile = oldFileEditor.getFile();
            Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
            if (document == null) {
              return; // couldn't find document
            }
            PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
            if (!BuckFileType.INSTANCE.equals(psiFile.getFileType())) {
              return; // file type isn't a Buck file
            }
            Runnable runnable = () -> BuildifierUtil.doReformat(project, virtualFile);
            LOGGER.info("Autoformatting " + virtualFile.getPath());
            CommandProcessor.getInstance()
                .executeCommand(
                    project,
                    runnable,
                    null,
                    null,
                    UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION,
                    document);
          }
        });
  }

  @Override
  public void disposeComponent() {
    if (mMessageBusConnection != null) {
      mMessageBusConnection.disconnect();
      mMessageBusConnection = null;
    }
  }

  @Override
  public void dispose() {
    disposeComponent();
  }
}
