/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

public class FakeBuildable extends AbstractBuildable {

  @Nullable
  private Path pathToOutputFile;

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    return pathToOutputFile;
  }

  /** @return this */
  public FakeBuildable setPathToOutputFile(@Nullable String pathToOutputFile) {
    return setPathToOutputFile(Paths.get(pathToOutputFile));

  }

  public FakeBuildable setPathToOutputFile(@Nullable Path pathToOutputFile) {
    this.pathToOutputFile = pathToOutputFile;
    return this;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

}
