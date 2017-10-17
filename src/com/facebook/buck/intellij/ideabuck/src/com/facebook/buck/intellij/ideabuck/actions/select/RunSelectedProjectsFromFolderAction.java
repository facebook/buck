package com.facebook.buck.intellij.ideabuck.actions.select;

import com.facebook.buck.intellij.ideabuck.file.BuckFileUtil;
import com.intellij.ide.IdeView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;

/**
 * Action to generate projects from the project tool view.
 */
public class RunSelectedProjectsFromFolderAction extends AnAction {

  @Override
  public void actionPerformed(AnActionEvent anActionEvent) {
    final Project project = anActionEvent.getProject();
    if (project == null) {
      return;
    }

    DataContext dataContext = anActionEvent.getDataContext();
    final IdeView view = LangDataKeys.IDE_VIEW.getData(dataContext);
    if (view == null) {
      return;
    }

    final PsiDirectory directory = view.getOrChooseDirectory();
    if (directory == null) {
      return;
    }

    String directoryString = directory.getVirtualFile().getPath();
    VirtualFile potentialBuckFile = BuckFileUtil.getBuckFile(directory.getVirtualFile());
    if (potentialBuckFile != null) {
      directoryString = potentialBuckFile.getParent().getPath();
    }

    String basepath = project.getBasePath();
    if (basepath == null) {
      return;
    }

    String relative = directoryString.replace(basepath, "");
    String target = "/" + relative + "/...";
    SelectProjectUtils.generateTargetForProject(target, project);
  }
}
