/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

class InferLogLine {
  private static final String SPLIT_TOKEN = "\t";

  private BuildTarget buildTarget;
  private ImmutableSortedSet<Flavor> flavors;
  private Path output;

  private InferLogLine(BuildTarget buildTarget, ImmutableSortedSet<Flavor> flavors, Path output) {
    this.buildTarget = buildTarget;
    this.flavors = flavors;
    this.output = output;
  }

  public static InferLogLine fromBuildTarget(BuildTarget target, Path output) {
    Preconditions.checkArgument(output.isAbsolute(), "Path must be absolute");
    return new InferLogLine(target, target.getFlavors(), output);
  }

  @Override
  public String toString() {
    return buildTarget + SPLIT_TOKEN + flavors + SPLIT_TOKEN + output;
  }
}
