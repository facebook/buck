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

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

public class CopyStep extends ShellStep {

  private final String source;
  private final String destination;
  private final boolean shouldRecurse;

  public CopyStep(String source, String destination, boolean shouldRecurse) {
    this.source = Preconditions.checkNotNull(source);
    this.destination = Preconditions.checkNotNull(destination);
    this.shouldRecurse = shouldRecurse;
  }

  public CopyStep(String source, String destination) {
    this(source, destination, false /* shouldRecurse */);
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return "cp";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(
      ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();

    args.add("cp");

    if (shouldRecurse) {
      args.add("-R");
    }

    args.add(source);
    args.add(destination);

    return args.build();
  }

  public String getSource() {
    return source;
  }

  public String getDestination() {
    return destination;
  }
}
