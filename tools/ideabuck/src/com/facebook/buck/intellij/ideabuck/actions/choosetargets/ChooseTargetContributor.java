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

package com.facebook.buck.intellij.ideabuck.actions.choosetargets;

import com.facebook.buck.intellij.ideabuck.actions.BuckQueryAction;
import com.facebook.buck.intellij.ideabuck.build.BuckBuildTargetAliasParser;
import com.facebook.buck.intellij.ideabuck.file.BuckFileUtil;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.intellij.ide.util.gotoByName.ChooseByNamePopup;
import com.intellij.navigation.ChooseByNameContributor;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

public class ChooseTargetContributor implements ChooseByNameContributor {

  private static final String ALIAS_SEPARATOR = "::";
  private static final String TARGET_NAME_SEPARATOR = ":";
  private static final String BUILD_DIR_SEPARATOR = "/";

  @Override
  public String[] getNames(Project project, boolean includeNonProjectItems) {
    List<String> names = new ArrayList<>();
    names.addAll(getNamesFromPathSuggestions(project));
    names.addAll(getNamesFromBuildTargetAlias(project));
    return names.toArray(new String[names.size()]);
  }

  private List<String> getNamesFromBuildTargetAlias(Project project) {
    List<String> names = new ArrayList<>();
    BuckBuildTargetAliasParser.parseAlias(project.getBasePath());
    for (Map.Entry<String, Set<String>> entry :
        BuckBuildTargetAliasParser.sTargetAlias.entrySet()) {
      String target = entry.getKey();
      Set<String> alias = entry.getValue();
      target += ALIAS_SEPARATOR + Joiner.on(',').join(alias);
      names.add(target);
    }
    return names;
  }

  private List<String> getNamesFromPathSuggestions(Project project) {
    CurrentInputText currentInputText = new CurrentInputText(project);
    if (currentInputText.buildDir == null) {
      return Collections.emptyList();
    }
    List<String> names = new ArrayList<>();
    names.addAll(getAllBuildTargetsUnderDirectory(project, currentInputText.buildDir));
    names.addAll(getNameSuggestionUnderPath(project, currentInputText.buildDir));
    if (!currentInputText.hasBuildRule) {
      names.addAll(getNameSuggestionUnderPath(project, getParentDir(currentInputText.buildDir)));
    }
    return names;
  }

  private List<String> getAllBuildTargetsUnderDirectory(Project project, String buildDir) {
    List<String> names = new ArrayList<>();
    // Try to get the relative path to the current input folder
    VirtualFile baseDir =
        project
            .getBaseDir()
            .findFileByRelativePath(appendSuffixIfNotEmpty(buildDir, File.separator));
    if (baseDir == null) {
      return names;
    }

    names.add("//" + appendSuffixIfNotEmpty(buildDir, BUILD_DIR_SEPARATOR) + "...");
    return names;
  }

  private static class CurrentInputText {
    public final String buildDir;
    public final boolean hasBuildRule;

    CurrentInputText(Project project) {
      ChooseByNamePopup chooseByNamePopup =
          project.getUserData(ChooseByNamePopup.CHOOSE_BY_NAME_POPUP_IN_PROJECT_KEY);
      if (chooseByNamePopup == null) {
        buildDir = null;
        hasBuildRule = false;
        return;
      }
      String currentText =
          chooseByNamePopup
              .getEnteredText()
              // Remove the begining //
              .replaceFirst("^/*", "");

      // check if we have as input a proper target
      int targetSeparatorIndex = currentText.lastIndexOf(TARGET_NAME_SEPARATOR);
      if (targetSeparatorIndex != -1) {
        hasBuildRule = true;
        buildDir = currentText.substring(0, targetSeparatorIndex);
      } else {
        hasBuildRule = false;
        buildDir = currentText;
      }
    }
  }

  private String getParentDir(String currentDir) {
    int lastDirSeparatorPosition = currentDir.lastIndexOf(File.separator);
    if (lastDirSeparatorPosition == -1) {
      return "";
    } else {
      return currentDir.substring(0, lastDirSeparatorPosition);
    }
  }

  private List<String> getNameSuggestionUnderPath(Project project, String buildDir) {
    List<String> names = new ArrayList<>();
    // Try to get the relative path to the current input folder
    VirtualFile baseDir =
        project
            .getBaseDir()
            .findFileByRelativePath(appendSuffixIfNotEmpty(buildDir, File.separator));
    if (baseDir == null) {
      return names;
    }

    // get the files under the base folder
    VirtualFile[] files = baseDir.getChildren();
    for (VirtualFile file : files) {
      names.addAll(getNameSuggestionForVirtualFile(project, file, buildDir));
    }
    return names;
  }

  private String appendSuffixIfNotEmpty(String source, String suffix) {
    if (!source.isEmpty()) {
      source += suffix;
    }
    return source;
  }

  private List<String> getNameSuggestionForVirtualFile(
      Project project, VirtualFile file, String buildDir) {
    // if the file is a directory we add it to the targets
    if (file.isDirectory()) {
      return new ArrayList<String>(
          Collections.singletonList(
              "//" + appendSuffixIfNotEmpty(buildDir, BUILD_DIR_SEPARATOR) + file.getName()));
    } else if (file.getName().equals(BuckFileUtil.getBuildFileName(project.getBasePath()))) {
      // if the file is a buck file  we parse it and add its target names to the list
      return getBuildTargetFromBuildProjectFile(project, buildDir);
    }
    return Collections.emptyList();
  }

  private List<String> getBuildTargetFromBuildProjectFile(Project project, String buildDir) {
    List<String> names = new ArrayList<>();
    names.add(getAllBuildTargetsInSameDirectory(buildDir));
    names.addAll(
        BuckQueryAction.execute(
            project,
            "//" + buildDir + TARGET_NAME_SEPARATOR,
            new Function<List<String>, Void>() {
              @Nullable
              @Override
              public Void apply(@Nullable List<String> strings) {
                ApplicationManager.getApplication()
                    .invokeLater(
                        new Runnable() {
                          public void run() {
                            ChooseByNamePopup chooseByNamePopup =
                                project.getUserData(
                                    ChooseByNamePopup.CHOOSE_BY_NAME_POPUP_IN_PROJECT_KEY);
                            // the user might have closed the window
                            if (chooseByNamePopup != null) {
                              // if we don't have them, just refresh the view when we do, if the
                              // window is still open
                              chooseByNamePopup.rebuildList(true);
                            }
                          }
                        });
                return null;
              }
            }));
    return names;
  }

  private String getAllBuildTargetsInSameDirectory(String buildDir) {
    return "//" + buildDir + TARGET_NAME_SEPARATOR;
  }

  @Override
  public NavigationItem[] getItemsByName(
      String name, String pattern, Project project, boolean includeNonProjectItems) {
    String alias = null;
    int index = name.lastIndexOf(ALIAS_SEPARATOR);
    if (index > 0) {
      alias = name.substring(index + ALIAS_SEPARATOR.length());
      name = name.substring(0, index);
    }
    return new NavigationItem[] {new ChooseTargetItem(name, alias)};
  }
}
