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

package com.facebook.buck.intellij.plugin.actions.choosetargets;

import com.facebook.buck.intellij.plugin.actions.BuckQueryAction;
import com.facebook.buck.intellij.plugin.build.BuckBuildTargetAliasParser;
import com.google.common.base.Joiner;
import com.intellij.ide.util.gotoByName.ChooseByNamePopup;
import com.intellij.navigation.ChooseByNameContributor;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Set;

public class ChooseTargetContributor implements ChooseByNameContributor {

  public static final String ALIAS_SEPARATOR = "::";
  public static final String TARGET_NAME_SEPARATOR = ":";

  private static Map<String, List<String>> otherTargets = new HashMap<String, List<String>>();

  public static void addToOtherTargets(
          final Project project,
          List<String> targets,
          String target) {
    otherTargets.put(target, targets);
    ApplicationManager.getApplication().invokeLater(
            new Runnable() {
              public void run() {
                project.getUserData(
                        ChooseByNamePopup.CHOOSE_BY_NAME_POPUP_IN_PROJECT_KEY).rebuildList(true);
              }
            });
  }

  public void addPathSugestions(List<String> names, Project project) {
    String currentText = project.getUserData(
            ChooseByNamePopup.CHOOSE_BY_NAME_POPUP_IN_PROJECT_KEY).getEnteredText();

    // Remove the begining //
    currentText = currentText.replaceFirst("^/*", "");

    // check if we have as input a proper target
    int currentTargetSeparatorIndex = currentText.lastIndexOf(TARGET_NAME_SEPARATOR);
    if (currentTargetSeparatorIndex != -1) {
      currentText = currentText.substring(0, currentText.lastIndexOf(TARGET_NAME_SEPARATOR));
    }
    // Try to get the relative path to the current input folder
    VirtualFile baseDir = project.getBaseDir().findFileByRelativePath(currentText + "/");

    if (baseDir == null) {
      // Try to get the relative path to the previous input folder
      if (currentText.lastIndexOf("/") != -1) {
        currentText = currentText.substring(0, currentText.lastIndexOf("/"));
      } else {
        // Get the base path if there is no previous folder
        currentText = "";
      }
      baseDir = project.getBaseDir().findFileByRelativePath(currentText);

      // If the base dir is still null, then we have a bad relative path
      if (baseDir == null) {
        return;
      }
    }
    // get the files under the base folder
    VirtualFile[] files = baseDir.getChildren();

    if (!currentText.isEmpty()) {
      currentText += "/";
    }

    for (VirtualFile file : files) {
      // if the file is a directory we add it to the targets
      if (file.isDirectory()) {
        names.add("//" + currentText + file.getName());
      }
      //if the file is a buck file  we parse it and add its target names to the list
      if (file.getName().equals("BUCK")) {
        String target = "//" + currentText.substring(0, currentText.length() - 1) + ":";

        if (otherTargets.containsKey(target)) {
          names.addAll(otherTargets.get(target));
        } else {
          BuckQueryAction.execute(project, target);
        }
      }
    }
  }

  @Override
  public String[] getNames(Project project, boolean includeNonProjectItems) {
    BuckBuildTargetAliasParser.parseAlias(project.getBasePath());
    List<String> names = new ArrayList<String>();

    addPathSugestions(names, project);

    for (Map.Entry<String, Set<String>> entry :
        BuckBuildTargetAliasParser.sTargetAlias.entrySet()) {
      String target = entry.getKey();
      Set<String> alias = entry.getValue();
      target += ALIAS_SEPARATOR + Joiner.on(',').join(alias);
      names.add(target);
    }

    return names.toArray(new String[names.size()]);
  }

  @Override
  public NavigationItem[] getItemsByName(
      String name,
      String pattern,
      Project project,
      boolean includeNonProjectItems) {
    String alias = null;
    int index = name.lastIndexOf(ALIAS_SEPARATOR);
    if (index > 0) {
      alias = name.substring(index + ALIAS_SEPARATOR.length());
      name = name.substring(0, index);
    }
    return new NavigationItem[] { new ChooseTargetItem(name, alias) };
  }
}
