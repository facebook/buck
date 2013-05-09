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

package com.facebook.buck.step.fs;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.shell.ShellStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

public class RmStep extends ShellStep {

  private final String patternToDelete;
  private final boolean shouldForceDeletion;
  private final boolean shouldRecurse;

  public RmStep(String patternToDelete, boolean shouldForceDeletion) {
    this(patternToDelete, shouldForceDeletion, false /* shouldRecurse */);
  }

  public RmStep(String patternToDelete,
                boolean shouldForceDeletion,
                boolean shouldRecurse) {
    this.patternToDelete = Preconditions.checkNotNull(patternToDelete);
    this.shouldForceDeletion = shouldForceDeletion;
    this.shouldRecurse = shouldRecurse;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("rm");

    if (shouldRecurse) {
      args.add("-r");
    }

    if (shouldForceDeletion) {
      args.add("-f");
    }

    args.add(patternToDelete);

    return args.build();
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return getDescription(context);
  }

}
