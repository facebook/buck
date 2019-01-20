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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Project-level preferences. */
@State(
    name = "BuckProjectSettingsProvider",
    storages = {@Storage("ideabuck.xml")})
public class BuckProjectSettingsProvider
    implements ProjectComponent, PersistentStateComponent<BuckProjectSettingsProvider.State> {

  private Project project;
  private BuckExecutableSettingsProvider buckExecutableSettingsProvider;
  private BuckCellSettingsProvider buckCellSettingsProvider;
  private State state = new State();
  private static final Logger LOG = Logger.getInstance(BuckProjectSettingsProvider.class);

  public static BuckProjectSettingsProvider getInstance(Project project) {
    return project.getComponent(BuckProjectSettingsProvider.class);
  }

  public BuckProjectSettingsProvider(Project project) {
    this(
        project,
        BuckCellSettingsProvider.getInstance(project),
        BuckExecutableSettingsProvider.getInstance(project));
  }

  @VisibleForTesting
  BuckProjectSettingsProvider(
      Project project,
      BuckCellSettingsProvider buckCellSettingsProvider,
      BuckExecutableSettingsProvider buckExecutableSettingsProvider) {
    this.project = project;
    this.buckExecutableSettingsProvider = buckExecutableSettingsProvider;
    this.buckCellSettingsProvider = buckCellSettingsProvider;
  }

  public Project getProject() {
    return project;
  }

  @Override
  public State getState() {
    return state;
  }

  @Override
  public void loadState(State state) {
    this.state = state;
    if (state.cells != null) {
      buckCellSettingsProvider.setCells(state.cells);
      state.cells = null;
    }
    if (state.adbExecutable != null || state.buckExecutable != null) {
      if (!buckExecutableSettingsProvider.getAdbExecutableOverride().isPresent()) {
        buckExecutableSettingsProvider.setAdbExecutableOverride(
            Optional.ofNullable(state.adbExecutable));
        state.adbExecutable = null;
      }
      if (!buckExecutableSettingsProvider.getBuckExecutableOverride().isPresent()) {
        buckExecutableSettingsProvider.setBuckExecutableOverride(
            Optional.ofNullable(state.buckExecutable));
        state.buckExecutable = null;
      }
    }
  }

  /**
   * Returns the path to a Buck executable that should explicitly be preferred to {@link
   * BuckExecutableDetector#getBuckExecutable()} for this project.
   *
   * @deprecated Use {@link BuckExecutableSettingsProvider#getBuckExecutableOverride()} directly.
   */
  @Deprecated
  public Optional<String> getBuckExecutableOverride() {
    return buckExecutableSettingsProvider.getBuckExecutableOverride();
  }

  /**
   * Sets the path of a Buck executable that should explicitly be preferred to {@link
   * BuckExecutableDetector#getBuckExecutable()} for this project.
   *
   * @deprecated Use {@link BuckExecutableSettingsProvider#setBuckExecutableOverride(Optional)}
   *     directly.
   */
  @Deprecated
  public void setBuckExecutableOverride(Optional<String> buckExecutableOverride) {
    buckExecutableSettingsProvider.setBuckExecutableOverride(buckExecutableOverride);
  }

  /**
   * Returns a path to a Buck executable to use with this project, or {@code null} if none can be
   * found.
   *
   * @deprecated Use {@link BuckExecutableSettingsProvider#resolveBuckExecutable()} directly.
   */
  @Deprecated
  @Nullable
  public String resolveBuckExecutable() {
    return buckExecutableSettingsProvider.resolveBuckExecutable();
  }

  /**
   * Returns the path to an adb executable that should explicitly be preferred to {@link
   * BuckExecutableDetector#getAdbExecutable()} for this project.
   *
   * @deprecated Use {@link BuckExecutableSettingsProvider#getAdbExecutableOverride()} directly.
   */
  @Deprecated
  public Optional<String> getAdbExecutableOverride() {
    return buckExecutableSettingsProvider.getAdbExecutableOverride();
  }

  /**
   * Sets the path of an adb executable that should explicitly be preferred to {@link
   * BuckExecutableDetector#getAdbExecutable()} for this project.
   *
   * @deprecated Use {@link BuckExecutableSettingsProvider#setAdbExecutableOverride(Optional)}
   *     directly.
   */
  @Deprecated
  public void setAdbExecutableOverride(Optional<String> adbExecutableOverride) {
    buckExecutableSettingsProvider.setAdbExecutableOverride(adbExecutableOverride);
  }

  /**
   * Returns a path to a Buck executable to use with this project, or {@code null} if none can be
   * found.
   *
   * @deprecated Use {@link BuckExecutableSettingsProvider#resolveAdbExecutable()} directly.
   */
  @Deprecated
  @Nullable
  public String resolveAdbExecutable() {
    return buckExecutableSettingsProvider.resolveAdbExecutable();
  }

  public boolean isShowDebugWindow() {
    return state.showDebug;
  }

  public void setShowDebugWindow(boolean showDebug) {
    state.showDebug = showDebug;
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

  @Override
  public void initComponent() {}

  @Override
  public void disposeComponent() {}

  @Override
  public String getComponentName() {
    return "BuckProjectSettingsProvider";
  }

  public Optional<String> getLastAlias() {
    return Optional.ofNullable(state.lastAlias);
  }

  public void setLastAlias(@Nullable String buildTarget) {
    state.lastAlias = buildTarget;
  }

  /** All settings are stored in this inner class. */
  public static class State {

    /** Remember the last used buck alias. */
    @Nullable public String lastAlias = null;

    /**
     * Optional buck executable to prefer to {@link BuckExecutableDetector#getBuckExecutable()}.
     *
     * @deprecated Moved to {@link BuckExecutableSettingsProvider.State#buckExecutable}.
     */
    @Nullable public String buckExecutable = null;

    /**
     * Optional adb executable to prefer to {@link BuckExecutableDetector#getAdbExecutable()}.
     *
     * @deprecated Moved to {@link BuckExecutableSettingsProvider.State#adbExecutable}.
     */
    @Nullable public String adbExecutable = null;

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

    /**
     * Buck cells supported in this project.
     *
     * @deprecated Moved to {@link BuckCellSettingsProvider.State#cells}.
     */
    @Deprecated @Nullable public List<BuckCell> cells = null;

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      State state = (State) o;
      return showDebug == state.showDebug
          && enableAutoDeps == state.enableAutoDeps
          && runAfterInstall == state.runAfterInstall
          && multiInstallMode == state.multiInstallMode
          && uninstallBeforeInstalling == state.uninstallBeforeInstalling
          && customizedInstallSetting == state.customizedInstallSetting
          && Objects.equal(lastAlias, state.lastAlias)
          && Objects.equal(buckExecutable, state.buckExecutable)
          && Objects.equal(adbExecutable, state.adbExecutable)
          && Objects.equal(customizedInstallSettingCommand, state.customizedInstallSettingCommand)
          && Objects.equal(cells, state.cells);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          lastAlias,
          buckExecutable,
          adbExecutable,
          showDebug,
          enableAutoDeps,
          runAfterInstall,
          multiInstallMode,
          uninstallBeforeInstalling,
          customizedInstallSetting,
          customizedInstallSettingCommand,
          cells);
    }
  }
}
