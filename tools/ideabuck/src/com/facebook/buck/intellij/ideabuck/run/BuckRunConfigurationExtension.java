package com.facebook.buck.intellij.ideabuck.run;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckQueryCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.ResultCallbackBuckHandler;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.facebook.buck.intellij.ideabuck.file.BuckFileUtil;
import com.google.common.base.Function;
import com.google.common.util.concurrent.FutureCallback;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunConfigurationExtension;
import com.intellij.execution.RunManager;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.impl.RunManagerImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.util.PathsList;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.jetbrains.annotations.NotNull;

public class BuckRunConfigurationExtension extends RunConfigurationExtension {

  private List<String> targetResults;
  static private File tempFile;

  @Override
  public <T extends RunConfigurationBase> void updateJavaParameters(T configuration,
      JavaParameters javaParameters, RunnerSettings runnerSettings) throws ExecutionException {
    Project currentProject = configuration.getProject();
    VirtualFile containingFile;
    VirtualFile buckFile;
    if (configuration instanceof ApplicationConfiguration) {
      containingFile = ((ApplicationConfiguration) configuration).getMainClass()
          .getContainingFile().getVirtualFile();
      buckFile = BuckFileUtil.getBuckFile(containingFile);
    } else {
      return;
    }
    List<String> additionalClasspath = new ArrayList<>();
    BuckBuildManager buildManager = BuckBuildManager.getInstance(currentProject);
    BuckQueryCommandHandler buckQueryCommandHandler =
        getBuckQueryCommandHandler(currentProject, buckFile);
    buckQueryCommandHandler.command().addParameter("owner(" + containingFile.getPath() + ")");
    buildManager.runInCurrentThreadPostEnd(
        buckQueryCommandHandler,
        () -> {
          if (targetResults != null) {
            for (String target : targetResults) {
              BuckModule buckModule = currentProject.getComponent(BuckModule.class);
              buckModule.attach(target);

              ResultCallbackBuckHandler buckAuditCommandHandler =
                  getBuckAuditCommandHandler(currentProject, buckFile, additionalClasspath, target);
              buckAuditCommandHandler.command().addParameter("classpath");
              buckAuditCommandHandler.command().addParameter(target);
              buckAuditCommandHandler.command().addParameter("--json");
              buildManager.runInCurrentThreadPostEnd(buckAuditCommandHandler,
                  () -> {
                  });
            }
          }
        });
    appendClasspath(javaParameters, currentProject, additionalClasspath);
  }

  private void appendClasspath(JavaParameters javaParameters, Project currentProject,
      List<String> additionalClasspath) {
    final PathsList classpath = javaParameters.getClassPath();
    for (String jar : additionalClasspath) {
      classpath.add(jar);
    }
    JsonObject jsonObject = null;
    try {
      jsonObject = new JsonParser()
          .parse(new FileReader(tempFile.getPath()))
          .getAsJsonObject();
    } catch (FileNotFoundException e) {
      return;
    }
    for (String target : targetResults) {
      if (jsonObject == null || jsonObject.getAsJsonObject("results") == null
          || jsonObject.getAsJsonObject("results").getAsJsonObject(target) == null
          || jsonObject.getAsJsonObject("results").getAsJsonObject(target).get("output")
          == null) {
        return;
      }
      classpath.add(currentProject.getBasePath() + "/" + jsonObject.getAsJsonObject("results")
          .getAsJsonObject(target).get("output").getAsString());
    }
  }

  @NotNull
  private ResultCallbackBuckHandler getBuckAuditCommandHandler(Project currentProject,
      VirtualFile buckFile, List<String> additionalClasspath, String target) {
    return new ResultCallbackBuckHandler(
        currentProject,
        buckFile.getParent(),
        BuckCommand.AUDIT,
        new FutureCallback<String>() {
          @Override
          public void onSuccess(@Nullable String result) {
            if (result == null
                || result.isEmpty()) {
              return;
            }
            JsonArray classpathArray = new JsonParser().parse(result)
                .getAsJsonObject().getAsJsonArray(target);
            if (classpathArray != null) {
              classpathArray.forEach(
                  jsonElement ->
                      additionalClasspath.add(jsonElement.getAsString())
              );
            }
          }

          @Override
          public void onFailure(Throwable t) {
          }
        });
  }

  @NotNull
  private BuckQueryCommandHandler getBuckQueryCommandHandler(Project currentProject,
      VirtualFile buckFile) {
    return new BuckQueryCommandHandler(
        currentProject,
        buckFile.getParent(),
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
            targetResults = strings;
            return null;
          }
        });
  }

  @Override
  protected boolean isApplicableFor(@NotNull RunConfigurationBase runConfigurationBase) {
    Project project = runConfigurationBase.getProject();
    if (tempFile == null) {
      try {
        tempFile = File.createTempFile("out", ".json");
      } catch (IOException e) {
        e.printStackTrace();
      }
      tempFile.deleteOnExit();
    }
    BuckBuildBeforeRun buckBuildBeforeRun = new BuckBuildBeforeRun(project);
    buckBuildBeforeRun.setTempFileOutput(tempFile.getAbsolutePath());
    BeforeRunTask buckBuild = buckBuildBeforeRun.createTask(runConfigurationBase);
    buckBuild.setEnabled(true);
    if (project == null) {
      return false;
    }

    if (!(runConfigurationBase instanceof ApplicationConfiguration)) {
      return false;
    }
    if (runConfigurationBase instanceof ApplicationConfiguration) {
      PsiClass mainClass = ((ApplicationConfiguration) runConfigurationBase).getMainClass();
      if (mainClass == null) {
        return false;
      }
      VirtualFile containingFile = mainClass.getContainingFile().getVirtualFile();

      VirtualFile buckFile = BuckFileUtil.getBuckFile(containingFile);

      if (buckFile == null) {
        return false;
      }
      //Remove the default build actions and rely on our own one.
      RunManager runManager = RunManager.getInstance(project);
      if (!(runManager instanceof RunManagerImpl)) {
        return false;
      }
      RunManagerImpl runManagerImpl = (RunManagerImpl) runManager;
      runManagerImpl
          .setBeforeRunTasks(runConfigurationBase, Collections.singletonList(buckBuild), false);
      return true;
    }
    return false;
  }
}
