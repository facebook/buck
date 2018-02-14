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

import com.facebook.buck.intellij.ideabuck.file.BuckFileUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;

/** Go to its BUCK file for current source file. */
public class GoToBuckFile extends DumbAwareAction {

  public static final String ACTION_TITLE = "Go To Buck file";

  public GoToBuckFile() {
    super(ACTION_TITLE);
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    final Project project = e.getProject();
    if (project == null) {
      return;
    }
    Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
    if (editor == null) {
      return;
    }
    final Document document = editor.getDocument();
    if (document == null) {
      return;
    }
    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);

    final VirtualFile file = BuckFileUtil.getBuckFile(virtualFile);
    if (file != null) {
      final OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file);
      // This is for better cursor position.
      final Navigatable n = descriptor.setUseCurrentWindow(false);
      if (!n.canNavigate()) {
        return;
      }
      n.navigate(true);
    }
  }
}
