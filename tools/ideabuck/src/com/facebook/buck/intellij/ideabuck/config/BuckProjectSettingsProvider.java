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

import com.google.common.base.Objects;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Project-level preferences. */
@State(
    name = "BuckProjectSettingsProvider",
    storages = {@Storage("ideabuck.xml")})
public class BuckProjectSettingsProvider extends AbstractProjectComponent
    implements PersistentStateComponent<BuckProjectSettingsProvider.State> {

  private BuckSettingsProvider myBuckSettingsProvider;
  private State state = new State();
  private static final Logger LOG = Logger.getInstance(BuckProjectSettingsProvider.class);

  public static BuckProjectSettingsProvider getInstance(Project project) {
    return project.getComponent(BuckProjectSettingsProvider.class);
  }

  public BuckProjectSettingsProvider(Project project, BuckSettingsProvider buckSettingsProvider) {
    super(project);
    myBuckSettingsProvider = buckSettingsProvider;
  }

  @Override
  public State getState() {
    return state;
  }

  @Override
  public void loadState(State state) {
    this.state = state;
    if (state.lastAlias == null) {
      /*
       * If user hasn't previously set an alias, migrate a legacy value
       * from BuckSettingsProvider.
       */
      state.lastAlias = myBuckSettingsProvider.getLastAliasForProject(myProject);
      myBuckSettingsProvider.removeAliasForProject(myProject);
    }
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

    @Override
    public int hashCode() {
      return Objects.hashCode(lastAlias);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      State state = (State) o;
      return Objects.equal(lastAlias, state.lastAlias);
    }
  }
}
