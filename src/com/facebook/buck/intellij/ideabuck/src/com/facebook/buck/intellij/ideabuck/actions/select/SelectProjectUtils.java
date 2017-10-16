package com.facebook.buck.intellij.ideabuck.actions.select;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.intellij.openapi.project.Project;

public class SelectProjectUtils {

  public static void generateTargetForProject(String target, Project project) {
    BuckBuildCommandHandler handler =
        new BuckBuildCommandHandler(project, project.getBaseDir(), BuckCommand.PROJECT);
    handler.command().addParameter(target);
    handler.command().addParameter("--ide");
    handler.command().addParameter("INTELLIJ");

    BuckModule buckModule = project.getComponent(BuckModule.class);
    BuckBuildManager buildManager = BuckBuildManager.getInstance(project);
    buildManager.setBuilding(project, true);
    buckModule.attach(target);
    buildManager.runBuckCommand(handler, "Running buck project on " + target);
    buildManager.setBuilding(project, false);
  }
}
