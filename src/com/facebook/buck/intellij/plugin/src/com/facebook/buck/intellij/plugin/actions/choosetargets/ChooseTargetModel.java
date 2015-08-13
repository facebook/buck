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

import com.intellij.ide.util.gotoByName.FilteringGotoByModel;
import com.intellij.navigation.ChooseByNameContributor;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.Nullable;

public class ChooseTargetModel
    extends FilteringGotoByModel<String> implements DumbAware {

  public ChooseTargetModel(Project project) {
    super(project, new ChooseByNameContributor[] { new ChooseTargetContributor() });
  }

  @Nullable
  @Override
  protected String filterValueFor(NavigationItem navigationItem) {
    return null;
  }

  @Override
  public String getPromptText() {
    return "Enter Buck build alias or full target";
  }

  @Override
  public String getNotInMessage() {
    return "No matches found";
  }

  @Override
  public String getNotFoundMessage() {
    return "No targets found";
  }

  @Nullable
  @Override
  public String getCheckBoxName() {
    return null;
  }

  @Override
  public char getCheckBoxMnemonic() {
    return SystemInfo.isMac ? 'P' : 'n';
  }

  @Override
  public boolean loadInitialCheckBoxState() {
    return false;
  }

  @Override
  public void saveInitialCheckBoxState(boolean state) {
  }

  @Override
  public String[] getSeparators() {
    return new String[0];
  }

  @Override
  public String getFullName(Object element) {
    return getElementName(element);
  }

  @Override
  public boolean willOpenEditor() {
    return false;
  }
}
