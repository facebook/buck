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
import java.util.Optional;

/**
 * Runs a list of rules one after another on the same thread, allowing each to access shared state.
 */
class BuildRulePipeline<State extends RulePipelineState> implements Runnable {

  private final StateHolder<State> stateHolder;
  private final List<BuildRulePipelineStage<State>> stages = new ArrayList<>();

  public BuildRulePipeline(BuildRulePipelineStage<State> rootRule, StateHolder<State> stateHolder) {
    this.stateHolder = stateHolder;
    buildPipeline(rootRule);
  }

  private void buildPipeline(BuildRulePipelineStage<State> firstStage) {
    BuildRulePipelineStage<State> current = firstStage;
    while (current != null) {
      current.setStateHolder(stateHolder);
      stages.add(current);
      current = current.getNextStage();
    }
  }

  @Override
  public void run() {
    try {
      Throwable error = null;
      for (BuildRulePipelineStage<State> stage : stages) {
        if (error == null) {
          stage.run();
          error = stage.getError();
        } else {
          // It doesn't really matter what error we use here -- we just want the future to
          // complete so that Buck doesn't hang. We use the real error in case it ever is shown
          // to the user (which does not happen as of the time of this comment, but for safety).
          stage.abort(error);
        }
        // If everything is working correctly, each stage in the pipeline should show itself
        // complete before we start the next one. Just a sanity check against weird behavior
        // creeping in.
        SettableFuture<Optional<BuildResult>> ruleFuture = stage.getFuture();
        checkState(ruleFuture.isDone() || ruleFuture.isCancelled());
      }
    } finally {
      stateHolder.close();
      stages.clear();
    }
  }
}
