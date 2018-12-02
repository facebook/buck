/*
 * Copyright 2018-present Facebook, Inc.
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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Settings for cell metadata for a project.
 *
 * @see {@link BuckCell}
 */
@State(
    name = "BuckCellSettingsProvider",
    storages = {@Storage("ideabuck/cells.xml")})
public class BuckCellSettingsProvider extends AbstractProjectComponent
    implements PersistentStateComponent<BuckCellSettingsProvider.State> {

  private BuckCellSettingsProvider.State state = new BuckCellSettingsProvider.State();
  private static final Logger LOG = Logger.getInstance(BuckCellSettingsProvider.class);

  public static BuckCellSettingsProvider getInstance(Project project) {
    return project.getComponent(BuckCellSettingsProvider.class);
  }

  public BuckCellSettingsProvider(Project project) {
    super(project);
  }

  @Override
  public void initComponent() {}

  @Override
  public void disposeComponent() {}

  public Project getProject() {
    return myProject;
  }

  @Override
  public BuckCellSettingsProvider.State getState() {
    return state;
  }

  @Override
  public void loadState(BuckCellSettingsProvider.State state) {
    this.state = state;
  }

  /** Returns the default buck cell for this project. */
  public Optional<BuckCell> getDefaultCell() {
    return state.cells.stream().findFirst().map(BuckCell::copy);
  }

  /** Returns a stream of buck cells in this project. */
  public List<BuckCell> getCells() {
    return state.cells.stream().map(BuckCell::copy).collect(Collectors.toList());
  }

  /** Returns a list of buck cell names in this project. */
  public List<String> getCellNames() {
    return state.cells.stream().map(BuckCell::getName).collect(Collectors.toList());
  }

  /** Sets a list of buck cells in this project. */
  public void setCells(List<BuckCell> cells) {
    ImmutableList.Builder<BuckCell> builder = ImmutableList.builder();
    for (BuckCell cell : cells) {
      builder.add(cell.copy());
    }
    this.state.cells = builder.build();
  }

  /** All settings are stored in this inner class. */
  public static class State {

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
      BuckCellSettingsProvider.State state = (BuckCellSettingsProvider.State) o;
      return Objects.equal(cells, state.cells);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(cells);
    }
  }
}
