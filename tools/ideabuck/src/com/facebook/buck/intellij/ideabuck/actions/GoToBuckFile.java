/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.actions;

import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;

/** Go to the build file for the current source file. */
public class GoToBuckFile extends DumbAwareAction {

  private VirtualFile findBuckFile(Project project) {
    if (project == null || project.isDefault()) {
      return null;
    }
    Editor currentEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
    if (currentEditor == null) {
      return null;
    }
    VirtualFile currentFile =
        FileDocumentManager.getInstance().getFile(currentEditor.getDocument());
    if (currentFile == null) {
      return null;
    }
    VirtualFile buckFile =
        BuckTargetLocator.getInstance(project).findBuckFileForVirtualFile(currentFile).orElse(null);
    if (buckFile == null || buckFile.equals(currentFile)) {
      return null;
    }
    return buckFile;
  }

  @Override
  public void update(AnActionEvent e) {
    Presentation presentation = e.getPresentation();
    VirtualFile buckFile = findBuckFile(e.getProject());
    if (buckFile == null) {
      presentation.setEnabledAndVisible(false);
    } else {
      presentation.setText("Go to " + buckFile.getName() + " file");
      presentation.setEnabledAndVisible(true);
    }
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      return;
    }
    VirtualFile buckFile = findBuckFile(project);
    if (buckFile != null) {
      final OpenFileDescriptor descriptor = new OpenFileDescriptor(e.getProject(), buckFile);
      // This is for better cursor position.
      final Navigatable n = descriptor.setUseCurrentWindow(false);
      if (!n.canNavigate()) {
        return;
      }
      n.navigate(true);
    }
  }
}
