/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.intellij.ideabuck.configurations;

import com.intellij.execution.BeforeRunTask;
import com.intellij.openapi.components.PersistentStateComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** {@link BeforeRunTask} implementation that executes a {@code buck} command. */
public class BuckCommandBeforeRunTask extends BeforeRunTask<BuckCommandBeforeRunTask>
    implements PersistentStateComponent<BuckCommandBeforeRunTask.State> {

  /** Persistent state for {@link BuckCommandBeforeRunTask}. */
  public static class State {
    public List<String> arguments = Collections.emptyList();
  }

  private State state = new State();

  public BuckCommandBeforeRunTask() {
    super(BuckCommandBeforeRunTaskProvider.ID);
  }

  public List<String> getArguments() {
    return new ArrayList<>(state.arguments);
  }

  public void setArguments(List<String> targets) {
    state.arguments = new ArrayList<>(targets);
  }

  @Nullable
  @Override
  public State getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull State state) {
    setArguments(state.arguments);
  }

  @Override
  public void noStateLoaded() {
    state = new State();
  }
}
