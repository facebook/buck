package com.facebook.buck.intellij.ideabuck.run;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckQueryCommandHandler;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.facebook.buck.intellij.ideabuck.file.BuckFileUtil;
import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.google.common.base.Function;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTask;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTaskProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.FileReader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.List;

public class BuckBuildBeforeRun extends ExternalSystemBeforeRunTaskProvider {

  private static final Key<ExternalSystemBeforeRunTask> ID = Key.create("Buck.BeforeRunTask");
  private static final ProjectSystemId SYSTEM_ID = new ProjectSystemId("buck");
  private List<String> targetResults;
  static private String tempFilePath;

  public BuckBuildBeforeRun(@NotNull Project project) {
    super(SYSTEM_ID, project, ID);
  }

  public void setTempFileOutput(String tempFileOutput) {
    tempFilePath = tempFileOutput;
  }

  @Override
  public String getName() {
    return "Buck Build";
  }

  @Override
  public String getDescription(ExternalSystemBeforeRunTask task) {
    return "Buck Build";
  }

  @NotNull
  @Override
  public ExternalSystemBeforeRunTask createTask(@NotNull RunConfiguration runConfiguration) {
    return new ExternalSystemBeforeRunTask(ID, SYSTEM_ID);
  }

  @Nullable
  @Override
  public Icon getIcon() {
    return BuckIcons.BUCK_TOOL_WINDOW_ICON;
  }

  @Override
  public boolean canExecuteTask(
      RunConfiguration configuration, ExternalSystemBeforeRunTask beforeRunTask
  ) {
    if (!(configuration instanceof ApplicationConfiguration)) {
      return false;
    }
    VirtualFile containingFile = ((ApplicationConfiguration) configuration).getMainClass()
        .getContainingFile().getVirtualFile();
    VirtualFile buckFile = BuckFileUtil.getBuckFile(containingFile);

    return buckFile != null;
  }

  @Override
  public boolean executeTask(
      final DataContext context,
      RunConfiguration configuration,
      ExecutionEnvironment env,
      ExternalSystemBeforeRunTask beforeRunTask
  ) {

    Project currentProject = configuration.getProject();

    AtomicBoolean successfulBuild = new AtomicBoolean(false);
    if (configuration instanceof ApplicationConfiguration) {
      VirtualFile containingFile = ((ApplicationConfiguration) configuration).getMainClass()
          .getContainingFile().getVirtualFile();

      VirtualFile buckFile = BuckFileUtil.getBuckFile(containingFile);

      if (buckFile == null) {
        return false;
      }

      BuckBuildManager buildManager = BuckBuildManager.getInstance(currentProject);
      BuckQueryCommandHandler buckQueryCommandHandler =
          getBuckQueryCommandHandler(currentProject, buckFile);
      buckQueryCommandHandler.command().addParameter("owner(" + containingFile.getPath() + ")");
      buildManager.runInCurrentThreadPostEnd(
          buckQueryCommandHandler,
          () -> {
            for (String target : targetResults) {
              buildTarget(currentProject, successfulBuild, buckFile, buildManager, target);
            }
          });
    }
    return successfulBuild.get();
  }

  private void buildTarget(Project currentProject, AtomicBoolean successfulBuild,
      VirtualFile buckFile, BuckBuildManager buildManager, String target) {
    BuckBuildCommandHandler buckBuildCommandHandler =
        new BuckBuildCommandHandler(
            currentProject,
            buckFile.getParent(),
            BuckCommand.BUILD,
            true
        );
    buckBuildCommandHandler.command()
        .addParameters("--deep", target, "--build-report", tempFilePath);
    BuckModule buckModule = currentProject.getComponent(BuckModule.class);
    buckModule.attach(target);
    buildManager.runBuckCommandWhileConnectedToBuck(
        buckBuildCommandHandler,
        "Building target: " + targetResults,
        buckModule);
    while (!buckBuildCommandHandler.isStarted()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    buckBuildCommandHandler.waitFor();
    JsonObject jsonObject = null;
    try {
      jsonObject = new JsonParser()
          .parse(new FileReader(tempFilePath))
          .getAsJsonObject();
    } catch (Exception e) {
      successfulBuild.set(successfulBuild.get());
    }
    if (jsonObject == null || jsonObject.getAsJsonPrimitive("success") == null) {
      successfulBuild.set(successfulBuild.get());
    } else {
      successfulBuild.set(
          successfulBuild.get() || jsonObject.getAsJsonPrimitive("success")
              .getAsBoolean());
    }
  }

  @NotNull
  private BuckQueryCommandHandler getBuckQueryCommandHandler(Project currentProject,
      VirtualFile buckFile) {
    return new BuckQueryCommandHandler(
        currentProject,
        buckFile.getParent(),
        BuckCommand.QUERY,
        new Function<List<String>, Void>() {
          @javax.annotation.Nullable
          @Override
          public Void apply(@javax.annotation.Nullable List<String> strings) {
            if (strings == null
                || strings.isEmpty()
                || strings.get(0) == null
                || strings.get(0).isEmpty()) {
              return null;
            }
            targetResults = strings;
            return null;
          }
        });
  }
}
