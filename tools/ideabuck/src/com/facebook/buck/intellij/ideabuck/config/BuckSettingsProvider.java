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

package com.facebook.buck.intellij.ideabuck.config;

import com.google.common.base.Strings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.ExportableApplicationComponent;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Load and save buck setting states across IDE restarts. */
@State(
    name = "BuckOptionsProvider",
    storages = {@Storage(file = StoragePathMacros.APP_CONFIG + "/buck.xml")})
public class BuckSettingsProvider
    implements PersistentStateComponent<BuckSettingsProvider.State>,
        ExportableApplicationComponent {

  private State state = new State();
  private static final Logger LOG = Logger.getInstance(BuckSettingsProvider.class);

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
  public void initComponent() {}

  @Override
  public void disposeComponent() {}

  @Override
  public File[] getExportFiles() {
    return new File[] {new File(PathManager.getOptionsPath() + File.separatorChar + "buck.xml")};
  }

  @Override
  public String getPresentableName() {
    return "Buck Options";
  }

  @Override
  public String getComponentName() {
    return "BuckOptionsProvider";
  }

  // Since earlier versions of this plugin may have persisted an empty string
  // to mean <none>, treat empty strings as null/none specified.
  private Optional<String> optionalFromNullableOrEmptyString(String s) {
    return Optional.ofNullable(Strings.emptyToNull(s));
  }

  /**
   * Returns the path to a Buck executable that should explicitly be preferred to whatever Buck is
   * discoverable by the {@link BuckExecutableDetector}.
   */
  public Optional<String> getBuckExecutableOverride() {
    return optionalFromNullableOrEmptyString(state.buckExecutable);
  }

  /**
   * Sets the path to a Buck executable that should explicitly be preferred to whatever Buck is
   * discoverable by the {@link BuckExecutableDetector}.
   */
  public void setBuckExecutableOverride(Optional<String> buckExecutableOverride) {
    this.state.buckExecutable = buckExecutableOverride.orElse(null);
  }

  /**
   * Finds a path to a Buck executable, honoring the user's override preferences and falling back to
   * whatever Buck can be discovered by {@link BuckExecutableDetector}. Returns {@code null} if none
   * is found.
   */
  public String resolveBuckExecutable() {
    String executable = state.buckExecutable;
    if (executable == null) {
      BuckExecutableDetector executableDetector = new BuckExecutableDetector();
      try {
        executable = executableDetector.getBuckExecutable();
      } catch (RuntimeException e) {
        // let the user insert the path to the executable
        LOG.error(
            e
                + ". You can specify the buck path from "
                + "Preferences/Settings > Tools > Buck > Buck Executable Path",
            e);
      }
    }
    return executable;
  }

  /**
   * Returns the path to an adb executable that should explicitly be preferred to whatever adb is
   * discoverable by the {@link BuckExecutableDetector}.
   */
  public Optional<String> getAdbExecutableOverride() {
    return optionalFromNullableOrEmptyString(this.state.adbExecutable);
  }

  /**
   * Sets the path to an adb executable that should explicitly be preferred to whatever adb is
   * discoverable by the {@link BuckExecutableDetector}.
   */
  public void setAdbExecutableOverride(Optional<String> adbExecutableOverride) {
    this.state.adbExecutable = adbExecutableOverride.orElse(null);
  }

  /**
   * Finds a path to an adb executable, honoring the user's override preferences and falling back to
   * whatever adb can be discovered by {@link BuckExecutableDetector}. Returns {@code null} if none
   * is found.
   */
  public String resolveAdbExecutable() {
    String executable = state.adbExecutable;
    if (executable == null) {
      BuckExecutableDetector executableDetector = new BuckExecutableDetector();
      try {
        executable = executableDetector.getAdbExecutable();
      } catch (RuntimeException e) {
        // let the user insert the path to the executable
        LOG.error(
            e
                + ". You can specify the adb path from "
                + "Preferences/Settings > Tools > Buck > Adb Executable Path",
            e);
      }
    }
    return executable;
  }

  public boolean isShowDebugWindow() {
    return state.showDebug;
  }

  public void setShowDebugWindow(boolean showDebug) {
    state.showDebug = showDebug;
  }

  public boolean isAutoDepsEnabled() {
    return state.enableAutoDeps;
  }

  public void setAutoDepsEnabled(boolean enableAutoDeps) {
    state.enableAutoDeps = enableAutoDeps;
  }

  public boolean isRunAfterInstall() {
    return state.runAfterInstall;
  }

  public void setRunAfterInstall(boolean runAfterInstall) {
    state.runAfterInstall = runAfterInstall;
  }

  public boolean isMultiInstallMode() {
    return state.multiInstallMode;
  }

  public void setMultiInstallMode(boolean multiInstallMode) {
    state.multiInstallMode = multiInstallMode;
  }

  public boolean isUninstallBeforeInstalling() {
    return state.uninstallBeforeInstalling;
  }

  public void setUninstallBeforeInstalling(boolean uninstallBeforeInstalling) {
    state.uninstallBeforeInstalling = uninstallBeforeInstalling;
  }

  public boolean isUseCustomizedInstallSetting() {
    return state.customizedInstallSetting;
  }

  public void setUseCustomizedInstallSetting(boolean customizedInstallSetting) {
    state.customizedInstallSetting = customizedInstallSetting;
  }

  public String getCustomizedInstallSettingCommand() {
    return state.customizedInstallSettingCommand;
  }

  public void setCustomizedInstallSettingCommand(String customizedInstallSettingCommand) {
    state.customizedInstallSettingCommand = customizedInstallSettingCommand;
  }

  public @Nullable String getLastAliasForProject(Project project) {
    return state.lastAlias.get(project.getBasePath());
  }

  public void setLastAliasForProject(Project project, String buildTarget) {
    state.lastAlias.put(project.getBasePath(), buildTarget);
  }

  /** All settings are stored in this inner class. */
  public static class State {

    /** Remember the last used buck alias for each historical project. */
    public Map<String, String> lastAlias = new HashMap<>();

    /** Buck executable to prefer to whatever can be found by the BuckExecutableDetector. */
    public String buckExecutable = null;

    /** Adb executable to prefer to whatever can be found by the BuckExecutableDetector. */
    public String adbExecutable = null;

    /** Enable the debug window for the plugin. */
    public boolean showDebug = false;

    /** Enable the buck auto deps for the plugin. */
    public boolean enableAutoDeps = false;

    /** "-r" parameter for "buck install" */
    public boolean runAfterInstall = true;

    /** "-x" parameter for "buck install" */
    public boolean multiInstallMode = false;

    /** "-u" parameter for "buck install" */
    public boolean uninstallBeforeInstalling = false;

    /** If true, use user's customized install string. */
    public boolean customizedInstallSetting = false;

    /** User's customized install command string, e.g. "-a -b -c". */
    public String customizedInstallSettingCommand = "";
  }
}
