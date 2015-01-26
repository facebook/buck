/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.step;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.util.Iterator;
import java.util.List;

import java.io.IOException;

public class CompositeStep implements Step, Iterable<Step> {

  private final ImmutableList<Step> steps;

  public CompositeStep(List<? extends Step> commands) {
    Preconditions.checkArgument(!commands.isEmpty(), "Must have at least one command");
    this.steps = ImmutableList.copyOf(commands);
  }

  @Override
  public int execute(ExecutionContext context) throws IOException, InterruptedException {
    for (Step step : steps) {
      int exitCode = step.execute(context);
      if (exitCode != 0) {
        return exitCode;
      }
    }
    return 0;
  }

  @Override
  public String getDescription(final ExecutionContext context) {
    return Joiner.on(" && ").join(Iterables.transform(steps,
        new Function<Step, String>() {
          @Override
          public String apply(Step step) {
            return step.getDescription(context);
          }
    }));
  }

  @Override
  public String getShortName() {
    return Joiner.on("_&&_").join(Iterables.transform(steps,
        new Function<Step, String>() {
      @Override
      public String apply(Step step) {
        return step.getShortName();
      }
    }));
  }

  @Override
  public Iterator<Step> iterator() {
    return steps.iterator();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    } else if (this == obj) {
      return true;
    } else if (!(getClass() == obj.getClass())) {
      return false;
    }

    CompositeStep that = (CompositeStep) obj;
    return Objects.equal(this.steps, that.steps);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(steps);
  }
}
