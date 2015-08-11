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

package com.facebook.buck.intellij.plugin.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.ExportableApplicationComponent;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Load and save buck setting states across IDE restarts.
 */
@State(
    name = "BuckOptionsProvider",
    storages = {
        @Storage(
            file = StoragePathMacros.APP_CONFIG + "/buck.xml"
        )}
)
public class BuckSettingsProvider implements PersistentStateComponent<BuckSettingsProvider.State>,
    ExportableApplicationComponent {

  private State state = new State();

  public static BuckSettingsProvider getInstance() {
    return ApplicationManager.getApplication().getComponent(BuckSettingsProvider.class);
  }

  @Override
  public State getState() {
    return state;
  }

  @Override
  public void loadState(State state) {
    this.state = state;
  }

  @Override
  public void initComponent() {
  }

  @Override
  public void disposeComponent() {
  }

  @Override
  public File[] getExportFiles() {
    return new File[]{new File(PathManager.getOptionsPath() + File.separatorChar + "buck.xml")};
  }

  @Override
  public String getPresentableName() {
    return "Buck Options";
  }

  @Override
  public String getComponentName() {
    return "BuckOptionsProvider";
  }

  /**
   * All settings are stored in this inner class.
   */
  public static class State {

    /**
     * Remember the last used buck alias for each historical project.
     */
    public Map<String, String> lastAlias = new HashMap<String, String>();

    /**
     * Path to buck executable.
     *
     * TODO(user): Use Buck's ExecutableFinder class.
     */
    public String buckExecutable = BuckExecutableDetector.detect();

    /**
     * "-r" parameter for "buck install"
     */
    public Boolean runAfterInstall = true;

    /**
     * "-x" parameter for "buck install"
     */
    public Boolean multiInstallMode = false;

    /**
     * "-u" parameter for "buck install"
     */
    public Boolean uninstallBeforeInstalling = false;

    /**
     * If use user's customized install string.
     */
    public Boolean customizedInstallSetting = false;

    /**
     * User's customized install command string, e.g. "-a -b -c".
     */
    public String customizedInstallSettingCommand = "";
  }
}
