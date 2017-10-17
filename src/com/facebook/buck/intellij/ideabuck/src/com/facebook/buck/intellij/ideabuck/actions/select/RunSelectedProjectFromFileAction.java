package com.facebook.buck.intellij.ideabuck.actions.select;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckQueryCommandHandler;
import com.facebook.buck.intellij.ideabuck.file.BuckFileUtil;
import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.google.common.base.Function;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Generate the iml file for the module that holds a file.
 */
public class RunSelectedProjectFromFileAction extends DumbAwareAction {

  public static final String ACTION_TITLE = "Generate Buck Project";

  public RunSelectedProjectFromFileAction() {
    super(ACTION_TITLE, ACTION_TITLE, BuckIcons.ACTION_PROJECT);
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
      BuckQueryCommandHandler handler =
          new BuckQueryCommandHandler(
              project,
              file.getParent(),
              BuckCommand.QUERY,
              new Function<List<String>, Void>() {
                @Nullable
                @Override
                public Void apply(@Nullable List<String> strings) {
                  if (strings == null
                      || strings.isEmpty()
                      || strings.get(0) == null
                      || strings.get(0).isEmpty()) {
                    return null;
                  }
                  String target = strings.get(0);
                  SelectProjectUtils.generateTargetForProject(target, project);
                  return null;
                }
              });
      BuckBuildManager buildManager = BuckBuildManager.getInstance(project);
      handler.command().addParameter("owner(" + virtualFile.getPath() + ")");
      buildManager.runBuckCommand(handler, "Searching for owning target.");
    }
  }
}
