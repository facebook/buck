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

package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.step.Step;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
public interface BuildRule extends Comparable<BuildRule>, HasBuildTarget, HasDependencies {

  @Override
  public BuildTarget getBuildTarget();

  @JsonProperty("name")
  public String getFullyQualifiedName();

  @JsonProperty("type")
  public String getType();

  public BuildableProperties getProperties();

  /**
   * @return the set of rules that must be built before this rule. Normally, this matches the value
   *     of the {@code deps} argument for this build rule in the build file in which it was defined.
   *     <p>
   *     However, there are special cases where other arguments pull in implicit dependencies (e.g.,
   *     the {@code keystore} argument in {@code android_binary}). In these cases, the implicit
   *     dependencies are also included in the set returned by this method. The value of the
   *     original {@code deps} argument, as defined in the build file, must be accessed via a
   *     custom getter provided by the build rule.
   */
  @Override
  public ImmutableSortedSet<BuildRule> getDeps();

  /**
   * @return key based on the BuildRule's state, including the transitive closure of its
   *     dependencies' keys.
   */
  public RuleKey getRuleKey();

  /** @return the same value as {@link #getFullyQualifiedName()} */
  @Override
  public String toString();

  public ImmutableList<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext);

  @Nullable
  public Path getPathToOutput();

  public ProjectFilesystem getProjectFilesystem();

}
