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

import com.facebook.buck.ide.intellij.projectview.shared.SharedConstants;
import com.facebook.buck.intellij.ideabuck.file.BuckFileUtil;
import com.facebook.buck.intellij.ideabuck.lang.BuckFile;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckArrayElementsImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckExpressionImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckPropertyLvalueImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckValueArrayImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckValueImpl;
import com.intellij.facet.Facet;
import com.intellij.facet.FacetManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.impl.source.tree.CompositeElement;
import com.intellij.util.PathUtil;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class MoveResourceFiles implements BulkFileListener {

  private static final Logger LOG = Logger.getInstance(MoveResourceFiles.class);

  // We need to use Reflection to call into the Android plugin
  private Class<?> androidFacet;
  private Method getAllResourceDirectories;
  private boolean loaded = false;

  /** Maps a new file name to the info needed to move / reopen it */
  private final Map<String, FileContext> creationContext = new HashMap<>();

  private static class FileContext {
    private final Project project;
    private final VirtualFile selection;
    private final Editor editor;

    private FileContext(Project project, VirtualFile selection, Editor editor) {
      this.project = project;
      this.selection = selection;
      this.editor = editor;
    }

    private String getRepositoryPath() {
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
  }

  // region BulkFileListener overrides

  @Override
  public void before(@NotNull List<? extends VFileEvent> list) {
    for (VFileEvent event : list) {
      Project project = getProject(event);

      if (project != null) {
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        Module[] modules = moduleManager.getModules();
        if (modules.length != 1) {
          continue; // This is NOT a Project View
        }
        Module module = modules[0];

        if (!module.getName().equals(SharedConstants.ROOT_MODULE_NAME)) {
          continue; // This is NOT a Project View
        }

        FacetManager facetManager = FacetManager.getInstance(module);
        Facet[] facets = facetManager.getAllFacets();
        if (facets.length != 1) {
          continue; // This is NOT a Project View
        }
        Facet facet = facets[0];

        if (!facet.getName().equals("Android")) {
          continue; // This is NOT a Project View
        }

        List<VirtualFile> resourceDirectories = getAllResourceDirectories(facet);
        if (resourceDirectories == null || resourceDirectories.size() != 1) {
          continue; // This is NOT a Project View
        }
        VirtualFile resourceDirectory = resourceDirectories.get(0);

        boolean inProjectViewResourceDirectory =
            event.getPath().startsWith(resourceDirectory.getPath());

        if (inProjectViewResourceDirectory) {
          FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
          VirtualFile[] selectedFiles = fileEditorManager.getSelectedFiles();
          if (selectedFiles.length != 1) {
            log(
                "Oh, dear. We have a new file in a Project View resource directory, but we have %d selected files",
                selectedFiles.length);
            showErrorDialog(
                project,
                "Error moving new file",
                "We have a new file in a Project View resource directory, but we have %d selected files and so don't know which BUCK file to examine",
                selectedFiles.length);
            continue; // we are expecting a SINGLE file, here
          }

          // We use event.getPath() because .getFile() == null
          creationContext.put(
              event.getPath(),
              new FileContext(
                  project, selectedFiles[0], fileEditorManager.getSelectedTextEditor()));
        }
      }
    }
  }

  @Override
  public void after(@NotNull List<? extends VFileEvent> list) {
    for (VFileEvent event : list) {
      String newFile = event.getPath();
      FileContext selectedContext = creationContext.remove(newFile);
      if (selectedContext == null) {
        continue;
      }

      moveResourceFile(newFile, selectedContext);
    }
  }

  // region BulkFileListener private utilities

  private List<VirtualFile> getAllResourceDirectories(Facet facet) {
    Class<? extends Facet> facetClass = facet.getClass();

    if (!loaded) {
      Class<?> clazz = null;
      Method method = null;
      try {
        clazz =
            Class.forName(
                "org.jetbrains.android.facet.AndroidFacet", true, facetClass.getClassLoader());
      } catch (Exception e) {
        // Leave clazz equal to null
        log("getAllResourceDirectories(): Exception %s in Class.forName()", e);
      }

      if (clazz != null) {
        try {
          method = clazz.getMethod("getAllResourceDirectories", (Class<?>[]) null);
        } catch (Exception e) {
          // Leave method equal to null
          log("getAllResourceDirectories(): Exception %s in clazz.getMethod()", e);
        }
      }

      androidFacet = clazz;
      getAllResourceDirectories = method;
      loaded = true;
    }

    if (androidFacet == null || getAllResourceDirectories == null) {
      if (androidFacet == null) {
        log("getAllResourceDirectories(): No clazz");
      }
      if (getAllResourceDirectories == null) {
        log("getAllResourceDirectories(): No method");
      }
      return null;
    }

    try {
      return (List<VirtualFile>) getAllResourceDirectories.invoke(facet);
    } catch (Exception e) {
      log("getAllResourceDirectories(): Exception %s calling facet.getAllResourceDirectories()", e);
      return null;
    }
  }

  private static Project getProject(VFileEvent event) {
    Object requestor = event.getRequestor();
    if (requestor instanceof PsiManager) {
      PsiManager psiManager = (PsiManager) requestor;
      return psiManager.getProject();
    }
    return null;
  }

  // endregion BulkFileListener private utilities

  // endregion BulkFileListener overrides

  // region Move resource file

  private void moveResourceFile(String newFile, FileContext selectedContext) {
    VirtualFile buckFile = BuckFileUtil.getBuckFile(selectedContext.selection);

    if (buckFile == null) {
      log("No BUCK file for %s?", selectedContext);
      showErrorDialog(
          selectedContext,
          "Can't move " + PathUtil.getFileName(newFile) + " to a resource module",
          "Can't find a BUCK file for \"%s\"",
          selectedContext.selection);
      return;
    }

    PsiManager psiManager = PsiManager.getInstance(selectedContext.project);
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
      showErrorDialog(
          selectedContext,
          "No android_res modules",
          "Could not find any android_res modules in %s - can't move the new resource file",
          buckFile);
      return;
    }

    if (androidRes.size() == 1) {
      moveTo(selectedContext, newFile, androidRes.get(0));
    } else {
      Path resourceFilePath = Paths.get(newFile).getFileName();
      String resourceFileName = resourceFilePath == null ? null : resourceFilePath.toString();
      PopupTargets popupTargets =
          new PopupTargets(
              newFile,
              selectedContext,
              "Please choose an android resource module"
                  + (resourceFileName == null ? "" : " for " + resourceFileName),
              androidRes);
      // PopupTargets.onChosen() will call this.moveTo()
      JBPopupFactory.getInstance()
          .createListPopup(popupTargets)
          .showInBestPositionFor(selectedContext.editor);
    }
  }

  private class PopupTargets extends BaseListPopupStep<String> {
    private final String newFile;
    private final FileContext selectedContext;
    private String selectedTarget;

    private PopupTargets(
        String newFile, FileContext selectedContext, @Nullable String title, List<String> values) {
      super(title, values);
      this.newFile = newFile;
      this.selectedContext = selectedContext;
    }

    @Override
    public PopupStep onChosen(String selectedTarget, boolean finalChoice) {
      this.selectedTarget = selectedTarget;
      return PopupStep.FINAL_CHOICE;
    }

    @Override
    public Runnable getFinalRunnable() {
      return () -> moveTo(selectedContext, newFile, selectedTarget);
    }
  }

  private void moveTo(FileContext selectedContext, String newFile, String selectedTarget) {
    String repo = selectedContext.getRepositoryPath();
    if (repo != null) {
      int colon = selectedTarget.lastIndexOf(':');
      selectedTarget = colon < 0 ? selectedTarget : selectedTarget.substring(0, colon);

      String basePath = selectedContext.project.getBasePath();
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
        showErrorDialog(selectedContext, "Error moving file", "New file is still at %s", newFile);
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
                  FileEditorManager fileEditorManager =
                      FileEditorManager.getInstance(selectedContext.project);
                  fileEditorManager.openFile(virtualFile, /*focusEditor=*/ true);
                } else {
                  showErrorDialog(
                      selectedContext,
                      "Can't reopen resource file",
                      "Could not find %s",
                      mangledTarget);
                }
              });
        } else {
          move(newTarget, newFilePath); // move back
          showErrorDialog(
              selectedContext, "Error creating symlink", "New file is still at %s", newFile);
        }
      }
    } else {
      showErrorDialog(selectedContext, "Can't find repo", "New file is still at %s", newFile);
    }
  }

  // region Move resource file private utilities

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

  // region Log and popup messages

  private static void log(String pattern, Object... parameters) {
    LOG.info(String.format(pattern, parameters));
  }

  private static void showErrorDialog(
      Project project, String title, String messagePattern, Object... parameters) {
    Messages.showErrorDialog(project, String.format(messagePattern, parameters), title);
  }

  private static void showErrorDialog(
      FileContext context, String title, String messagePattern, Object... parameters) {
    Messages.showErrorDialog(context.project, String.format(messagePattern, parameters), title);
  }

  // endregion Log and popup messages
}
