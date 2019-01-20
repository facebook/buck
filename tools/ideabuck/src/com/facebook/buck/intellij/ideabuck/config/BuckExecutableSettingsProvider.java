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
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Ideabuck preferences related to external executables. */
@State(
    name = "BuckExecutableSettingsProvider",
    storages = {@Storage("ideabuck/executables.xml")})
public class BuckExecutableSettingsProvider
    implements ProjectComponent, PersistentStateComponent<BuckExecutableSettingsProvider.State> {

  private BuckExecutableDetector executableDetector;
  private State state = new State();
  private static final Logger LOG = Logger.getInstance(BuckExecutableSettingsProvider.class);

  public static BuckExecutableSettingsProvider getInstance(Project project) {
    return project.getComponent(BuckExecutableSettingsProvider.class);
  }

  public BuckExecutableSettingsProvider() {
    this(BuckExecutableDetector.newInstance());
  }

  @VisibleForTesting
  BuckExecutableSettingsProvider(BuckExecutableDetector executableDetector) {
    this.executableDetector = executableDetector;
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
   * Returns a path to an adb executable to use with this project, or {@code null} if none can be
   * found.
   */
  @Nullable
  public String resolveAdbExecutable() {
    String executable = state.adbExecutable;
    if (executable == null) {
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

  /**
   * Returns the path to a buildifer executable that should explicitly be preferred to {@link
   * BuckExecutableDetector#getBuildifierExecutable()} for this project.
   */
  public Optional<String> getBuildifierExecutableOverride() {
    return Optional.ofNullable(state.buildifierExecutable);
  }

  /**
   * Sets the path of a buildifier executable that should explicitly be preferred to {@link
   * BuckExecutableDetector#getBuildifierExecutable()} for this project.
   */
  public void setBuildifierExecutableOverride(Optional<String> buildifierExecutableOverride) {
    this.state.buildifierExecutable = buildifierExecutableOverride.orElse(null);
  }

  /**
   * Returns a path to a buildifier executable to use with this project, or {@code null} if none can
   * be found.
   */
  @Nullable
  public String resolveBuildifierExecutable() {
    String executable = state.buildifierExecutable;
    if (executable == null) {
      try {
        executable = executableDetector.getBuildifierExecutable();
      } catch (RuntimeException e) {
        // let the user insert the path to the executable
        LOG.error(
            e
                + ". You can specify the buildifier path from "
                + "Preferences/Settings > Tools > Buck",
            e);
      }
    }
    return executable;
  }

  /**
   * Returns the path to a buildifer executable that should explicitly be preferred to {@link
   * BuckExecutableDetector#getBuildozerExecutable()} for this project.
   */
  public Optional<String> getBuildozerExecutableOverride() {
    return Optional.ofNullable(state.buildozerExecutable);
  }

  /**
   * Sets the path of a buildozer executable that should explicitly be preferred to {@link
   * BuckExecutableDetector#getBuildozerExecutable()} for this project.
   */
  public void setBuildozerExecutableOverride(Optional<String> buildozerExecutableOverride) {
    this.state.buildozerExecutable = buildozerExecutableOverride.orElse(null);
  }

  /**
   * Returns a path to a buildozer executable to use with this project, or {@code null} if none can
   * be found.
   */
  @Nullable
  public String resolveBuildozerExecutable() {
    String executable = state.buildozerExecutable;
    if (executable == null) {
      try {
        executable = executableDetector.getBuildozerExecutable();
      } catch (RuntimeException e) {
        // let the user insert the path to the executable
        LOG.error(
            e
                + ". You can specify the buildozer path from "
                + "Preferences/Settings > Tools > Buck",
            e);
      }
    }
    return executable;
  }

  @Override
  public String getComponentName() {
    return "BuckExecutableSettingsProvider";
  }

  /** All settings are stored in this inner class. */
  public static class State {

    /** Optional buck executable to prefer to {@link BuckExecutableDetector#getBuckExecutable()}. */
    @Nullable public String buckExecutable = null;

    /** Optional adb executable to prefer to {@link BuckExecutableDetector#getAdbExecutable()}. */
    @Nullable public String adbExecutable = null;

    /**
     * Optional buildifier executable to prefer to {@link
     * BuckExecutableDetector#getBuildifierExecutable()}.
     */
    @Nullable public String buildifierExecutable = null;

    /**
     * Optional buildozer executable to prefer to {@link
     * BuckExecutableDetector#getBuildozerExecutable()}.
     */
    @Nullable public String buildozerExecutable = null;

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      State state = (State) o;
      return Objects.equal(buckExecutable, state.buckExecutable)
          && Objects.equal(adbExecutable, state.adbExecutable)
          && Objects.equal(buildifierExecutable, state.buildifierExecutable)
          && Objects.equal(buildozerExecutable, state.buildozerExecutable);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          buckExecutable, adbExecutable, buildifierExecutable, buildozerExecutable);
    }
  }
}
