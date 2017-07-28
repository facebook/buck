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

package com.facebook.buck.intellij.ideabuck.fixup;

import com.facebook.buck.intellij.ideabuck.file.BuckFileUtil;
import com.facebook.buck.intellij.ideabuck.lang.BuckFile;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckArrayElementsImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckExpressionImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckPropertyLvalueImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckValueArrayImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckValueImpl;
import com.intellij.facet.Facet;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.impl.source.tree.CompositeElement;
import com.intellij.util.PathUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

class MoveResourceFiles implements FileCreateHandler {

  private static final Logger LOG = Logger.getInstance(MoveResourceFiles.class);

  // region FileCreateHandler overrides

  @Override
  public void onFileCreate(VFileCreateEvent event, Facet facet) {
    Project project = facet.getModule().getProject();

    FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
    VirtualFile[] selectedFiles = fileEditorManager.getSelectedFiles();
    if (selectedFiles.length != 1) {
      log(
          "Oh, dear. We have a new file in a Project View resource directory, but we have %d selected files",
          selectedFiles.length);
      ErrorDialog.showErrorDialog(
          project,
          "Error moving new file",
          "We have a new file in a Project View resource directory, but we have %d selected files and so don't know which BUCK file to examine",
          selectedFiles.length);
      return; // we are expecting a SINGLE file, here
    }
    VirtualFile selection = selectedFiles[0];

    Editor editor = fileEditorManager.getSelectedTextEditor();

    moveResourceFile(event.getPath(), project, selection, editor);
  }

  // endregion FileCreateHandler overrides

  // region Move resource file

  private void moveResourceFile(
      String newFile, Project project, VirtualFile selection, Editor editor) {
    VirtualFile buckFile = BuckFileUtil.getBuckFile(selection);

    if (buckFile == null) {
      log("No BUCK file for %s?", selection.getName());
      ErrorDialog.showErrorDialog(
          project,
          "Can't move " + PathUtil.getFileName(newFile) + " to a resource module",
          "Can't find a BUCK file for \"%s\"",
          selection.getName());
      return;
    }

    PsiManager psiManager = PsiManager.getInstance(project);
    BuckFile parsed = new BuckFile(psiManager.findViewProvider(buckFile));

    List<String> androidRes = new ArrayList<>();
    parsed.accept(
        new PsiRecursiveElementWalkingVisitor() {
          @Override
          public void visitElement(PsiElement element) {
            if (element instanceof BuckPropertyLvalueImpl && element.getText().equals("deps")) {
              PsiElement expression = getNextCompositeSibling(element);
              if (isNot(expression, BuckExpressionImpl.class)) {
                return;
              }
              PsiElement value = expression.getFirstChild();
              if (isNot(value, BuckValueImpl.class)) {
                return;
              }
              PsiElement array = value.getFirstChild();
              if (isNot(array, BuckValueArrayImpl.class)) {
                return;
              }
              PsiElement arrayElements = getFirstCompositeChild(array);
              if (isNot(arrayElements, BuckArrayElementsImpl.class)) {
                return;
              }
              for (PsiElement dependency : arrayElements.getChildren()) {
                String target = dependency.getText();
                // TODO(shemitz) get the startsWith target String from .buckconfig!
                if (target.startsWith('\"' + "//android_res/")) {
                  androidRes.add(target.substring(1, target.length() - 1));
                }
              }
              stopWalking();
              return;
            }
            super.visitElement(element);
          }

          private boolean isNot(PsiElement element, Class<? extends PsiElement> type) {
            if (type.isInstance(element)) {
              return false; // !(element instanceof type)
            }
            log(
                "Expecting a %s, got a %s",
                type.getSimpleName(), element.getClass().getSimpleName());
            stopWalking();
            return true; // !(element instanceof type)
          }
        });

    // TODO(shemitz) filter out targets that can't host this file (ie, already have a res/colors.xml or whatever)

    if (androidRes.isEmpty()) {
      ErrorDialog.showErrorDialog(
          project,
          "No android_res modules",
          "Could not find any android_res modules in %s - can't move the new resource file",
          buckFile);
      return;
    }

    if (androidRes.size() == 1) {
      moveTo(project, selection, newFile, androidRes.get(0));
    } else {
      Path resourceFilePath = Paths.get(newFile).getFileName();
      String resourceFileName = resourceFilePath == null ? null : resourceFilePath.toString();
      PopupTargets popupTargets =
          new PopupTargets(
              newFile,
              project,
              selection,
              "Please choose an android resource module"
                  + (resourceFileName == null ? "" : " for " + resourceFileName),
              androidRes);
      // PopupTargets.onChosen() will call this.moveTo()
      JBPopupFactory.getInstance().createListPopup(popupTargets).showInBestPositionFor(editor);
    }
  }

  private class PopupTargets extends BaseListPopupStep<String> {
    private final String newFile;
    private final Project project;
    private final VirtualFile selection;
    private String selectedTarget;

    private PopupTargets(
        String newFile,
        Project project,
        VirtualFile selection,
        @Nullable String title,
        List<String> values) {
      super(title, values);
      this.newFile = newFile;
      this.project = project;
      this.selection = selection;
    }

    @Override
    public PopupStep onChosen(String selectedTarget, boolean finalChoice) {
      this.selectedTarget = selectedTarget;
      return PopupStep.FINAL_CHOICE;
    }

    @Override
    public Runnable getFinalRunnable() {
      return () -> moveTo(project, selection, newFile, selectedTarget);
    }
  }

  private void moveTo(
      Project project, VirtualFile selection, String newFile, String selectedTarget) {
    String repo = getRepositoryPath(project, selection);
    if (repo != null) {
      int colon = selectedTarget.lastIndexOf(':');
      selectedTarget = colon < 0 ? selectedTarget : selectedTarget.substring(0, colon);

      String basePath = project.getBasePath();
      String newFileSuffix = newFile.substring(basePath.length());
      Path newTarget = Paths.get(repo, selectedTarget, newFileSuffix);

      String targetSuffix = selectedTarget.substring("//android_res/".length());
      String mangledPath = targetSuffix.replace('/', '_');

      String dirnameNewFile = PathUtil.getParentPath(newFile);
      String basenameNewFile = PathUtil.getFileName(newFile);
      Path mangledTarget = Paths.get(dirnameNewFile, mangledPath + "_" + basenameNewFile);

      // Move newFile to newTarget, create symlink mangledTarget -> newTarget
      Path newFilePath = Paths.get(newFile);

      // mv newFilePath to newTarget
      boolean moved = move(newFilePath, newTarget);
      if (!moved) {
        ErrorDialog.showErrorDialog(
            project, "Error moving file", "New file is still at %s", newFile);
      } else {
        // ln -s newTarget mangledTarget
        boolean linked = createSymbolicLink(mangledTarget, newTarget);
        if (linked) {
          // Open mangledTarget in editor
          VirtualFileManager virtualFileManager = VirtualFileManager.getInstance();
          virtualFileManager.asyncRefresh(
              () -> {
                VirtualFile virtualFile =
                    virtualFileManager.findFileByUrl("file://" + mangledTarget.toString());
                if (virtualFile != null) {
                  FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
                  fileEditorManager.openFile(virtualFile, /*focusEditor=*/ true);
                } else {
                  ErrorDialog.showErrorDialog(
                      project, "Can't reopen resource file", "Could not find %s", mangledTarget);
                }
              });
        } else {
          move(newTarget, newFilePath); // move back
          ErrorDialog.showErrorDialog(
              project, "Error creating symlink", "New file is still at %s", newFile);
        }
      }
    } else {
      ErrorDialog.showErrorDialog(project, "Can't find repo", "New file is still at %s", newFile);
    }
  }

  // region Move resource file private utilities

  private static String getRepositoryPath(Project project, VirtualFile selection) {
    String basePath = project.getBasePath();
    String selectionName = selection.getPath();
    String selectionSuffix = selectionName.substring(basePath.length());
    if (selectionSuffix.startsWith(File.separator)) {
      selectionSuffix = selectionSuffix.substring(1);
    }
    Path selectionPath = Paths.get(selectionName);
    String realSelectionPath;
    try {
      realSelectionPath = selectionPath.toRealPath().toString();
    } catch (IOException e) {
      return null;
    }
    return realSelectionPath.endsWith(selectionSuffix)
        ? realSelectionPath.substring(0, realSelectionPath.length() - selectionSuffix.length())
        : null;
  }

  /**
   * Returns (what would be) the next child in {@link PsiElement#getParent() getParent()}.{@link
   * PsiElement#getChildren() getChildren()} (even if element doesn't actually appears in {@code
   * getParent().getChildren()})
   */
  private static PsiElement getNextCompositeSibling(PsiElement element) {
    for (PsiElement sibling = element.getNextSibling();
        sibling != null;
        sibling = sibling.getNextSibling()) {
      if (sibling.getNode() instanceof CompositeElement) {
        return sibling;
      }
    }
    return null;
  }

  /**
   * Returns the first child in {@link PsiElement#getParent() getParent()}.{@link
   * PsiElement#getChildren() getChildren()}
   */
  private static PsiElement getFirstCompositeChild(PsiElement element) {
    PsiElement child = element.getFirstChild();
    return child == null
        ? null
        : child.getNode() instanceof CompositeElement ? child : getNextCompositeSibling(child);
  }

  private static boolean move(Path source, Path target) {
    try {
      Files.move(source, target);
      return true;
    } catch (IOException e) {
      log("move(%s, %s): %s", source, target, e);
      return false;
    }
  }

  private static boolean createSymbolicLink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return true;
    } catch (IOException e) {
      log("createSymbolicLink(%s, %s): %s", link, target, e);
      return false;
    }
  }

  // endregion Move resource file private utilities

  // endregion Move resource file

  // region Log messages

  private static void log(String pattern, Object... parameters) {
    LOG.info(String.format(pattern, parameters));
  }

  // endregion Log messages
}
