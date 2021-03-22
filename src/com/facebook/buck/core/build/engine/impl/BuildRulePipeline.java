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

package com.facebook.buck.core.build.engine.impl;

import static com.google.common.base.Preconditions.checkState;

import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.rules.pipeline.RulePipelineState;
import com.facebook.buck.core.rules.pipeline.StateHolder;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Runs a list of rules one after another on the same thread, allowing each to access shared state.
 */
class BuildRulePipeline<State extends RulePipelineState> implements Runnable {

  @Nullable private StateHolder<State> stateHolder;
  private final List<BuildRulePipelineStage<State>> rules = new ArrayList<>();

  public BuildRulePipeline(BuildRulePipelineStage<State> rootRule, StateHolder<State> stateHolder) {
    this.stateHolder = stateHolder;
    buildPipeline(rootRule);
  }

  private void buildPipeline(BuildRulePipelineStage<State> firstStage) {
    BuildRulePipelineStage<State> current = firstStage;
    while (current != null) {
      BuildRulePipelineStage<State> stage = current;
      stage.setPipeline(this);
      rules.add(stage);
      current = stage.getNextStage();
    }
  }

  public StateHolder<State> getStateHolder() {
    return Objects.requireNonNull(stateHolder);
  }

  public State getState() {
    return getStateHolder().getState();
  }

  @Override
  public void run() {
    try {
      Throwable error = null;
      for (BuildRulePipelineStage<State> rule : rules) {
        if (error == null) {
          rule.run();
          error = rule.getError();
        } else {
          // It doesn't really matter what error we use here -- we just want the future to
          // complete so that Buck doesn't hang. We use the real error in case it ever is shown
          // to the user (which does not happen as of the time of this comment, but for safety).
          rule.abort(error);
        }
        // If everything is working correctly, each rule in the pipeline should show itself
        // complete before we start the next one. Just a sanity check against weird behavior
        // creeping in.
        SettableFuture<Optional<BuildResult>> ruleFuture = rule.getFuture();
        checkState(ruleFuture.isDone() || ruleFuture.isCancelled());
      }
    } finally {
      getState().close();
      stateHolder = null;
      rules.clear();
    }
  }
}
