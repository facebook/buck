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
import com.facebook.buck.step.Step;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;

public class CopyStep implements Step {

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

  public CopyStep(Path source, Path destination, boolean shouldRecurse) {
    this(source.toString(), destination.toString(), shouldRecurse);
  }

  public CopyStep(Path source, Path destination) {
    this(source, destination, false /* shouldRecurse */);
  }

  @Override
  public String getShortName() {
    return "cp";
  }

  @Override
  public String getDescription (ExecutionContext context) {
    Function<String, String> pathRelativizer = context.getProjectFilesystem().getPathRelativizer();
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("cp");
    if (shouldRecurse) {
      args.add("-R");
    }
    args.add(pathRelativizer.apply(source));
    args.add(pathRelativizer.apply(destination));
    return Joiner.on(" ").join(args.build());
  }

  public String getSource() {
    return source;
  }

  public String getDestination() {
    return destination;
  }

  public boolean isRecursive() {
    return shouldRecurse;
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      if (shouldRecurse) {
        context.getProjectFilesystem().copyFolder(source, destination);
      } else {
        context.getProjectFilesystem().copyFile(source, destination);
      }
      return 0;
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
  }
}
