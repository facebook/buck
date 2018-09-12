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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Project-level preferences. */
@State(
    name = "BuckProjectSettingsProvider",
    storages = {@Storage("ideabuck.xml")})
public class BuckProjectSettingsProvider extends AbstractProjectComponent
    implements PersistentStateComponent<BuckProjectSettingsProvider.State> {

  private BuckExecutableDetector buckExecutableDetector;
  private State state = new State();
  private static final Logger LOG = Logger.getInstance(BuckProjectSettingsProvider.class);

  public static BuckProjectSettingsProvider getInstance(Project project) {
    return project.getComponent(BuckProjectSettingsProvider.class);
  }

  public BuckProjectSettingsProvider(Project project) {
    this(project, BuckExecutableDetector.newInstance());
  }

  @VisibleForTesting
  BuckProjectSettingsProvider(Project project, BuckExecutableDetector buckExecutableDetector) {
    super(project);
    this.buckExecutableDetector = buckExecutableDetector;
  }

  public Project getProject() {
    return myProject;
  }

  @Override
  public State getState() {
    return state;
  }

  @Override
  public void loadState(State state) {
    this.state = state;
  }

  /**
   * Returns the path to a Buck executable that should explicitly be preferred to {@link
   * BuckExecutableDetector#getBuckExecutable()} for this project.
   */
  public Optional<String> getBuckExecutableOverride() {
    return Optional.ofNullable(state.buckExecutable);
  }

  /**
   * Sets the path of a Buck executable that should explicitly be preferred to {@link
   * BuckExecutableDetector#getBuckExecutable()} for this project.
   */
  public void setBuckExecutableOverride(Optional<String> buckExecutableOverride) {
    this.state.buckExecutable = buckExecutableOverride.orElse(null);
  }

  /**
   * Returns a path to a Buck executable to use with this project, or {@code null} if none can be
   * found.
   */
  @Nullable
  public String resolveBuckExecutable() {
    String executable = state.buckExecutable;
    if (executable == null) {
      try {
        executable = buckExecutableDetector.getBuckExecutable();
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
   * Returns the path to an adb executable that should explicitly be preferred to {@link
   * BuckExecutableDetector#getAdbExecutable()} for this project.
   */
  public Optional<String> getAdbExecutableOverride() {
    return Optional.ofNullable(state.adbExecutable);
  }

  /**
   * Sets the path of an adb executable that should explicitly be preferred to {@link
   * BuckExecutableDetector#getAdbExecutable()} for this project.
   */
  public void setAdbExecutableOverride(Optional<String> adbExecutableOverride) {
    this.state.adbExecutable = adbExecutableOverride.orElse(null);
  }

  /**
   * Returns a path to a Buck executable to use with this project, or {@code null} if none can be
   * found.
   */
  @Nullable
  public String resolveAdbExecutable() {
    String executable = state.adbExecutable;
    if (executable == null) {
      try {
        executable = buckExecutableDetector.getAdbExecutable();
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

  /** Returns a list of buck cells in this project. */
  public List<BuckCell> getCells() {
    List<BuckCell> result = new ArrayList<>(state.cells.size());
    for (BuckCell cell : state.cells) {
      result.add(cell.copy());
    }
    return result;
  }

  /** Sets a list of buck cells in this project. */
  public void setCells(List<BuckCell> cells) {
    ImmutableList.Builder<BuckCell> builder = ImmutableList.builder();
    for (BuckCell cell : cells) {
      builder.add(cell.copy());
    }
    this.state.cells = builder.build();
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

    /** Optional buck executable to prefer to {@link BuckExecutableDetector#getBuckExecutable()}. */
    @Nullable public String buckExecutable = null;

    /** Optional adb executable to prefer to {@link BuckExecutableDetector#getAdbExecutable()}. */
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

    /** Buck cells supported in this project. */
    public List<BuckCell> cells = Lists.newArrayList(new BuckCell());

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
