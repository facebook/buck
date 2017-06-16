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
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepRunner;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import javax.annotation.Nullable;

@JsonAutoDetect(
  fieldVisibility = JsonAutoDetect.Visibility.NONE,
  getterVisibility = JsonAutoDetect.Visibility.NONE,
  setterVisibility = JsonAutoDetect.Visibility.NONE
)
public interface BuildRule extends Comparable<BuildRule> {

  BuildTarget getBuildTarget();

  @JsonProperty("name")
  @JsonView(JsonViews.MachineReadableLog.class)
  default String getFullyQualifiedName() {
    return getBuildTarget().getFullyQualifiedName();
  }

  @JsonProperty("type")
  @JsonView(JsonViews.MachineReadableLog.class)
  String getType();

  /**
   * @return the set of rules that must be built before this rule. Normally, this matches the value
   *     of the {@code deps} argument for this build rule in the build file in which it was defined.
   *     <p>However, there are special cases where other arguments pull in implicit dependencies
   *     (e.g., the {@code keystore} argument in {@code android_binary}). In these cases, the
   *     implicit dependencies are also included in the set returned by this method. The value of
   *     the original {@code deps} argument, as defined in the build file, must be accessed via a
   *     custom getter provided by the build rule.
   */
  SortedSet<BuildRule> getBuildDeps();

  /** @return the same value as {@link #getFullyQualifiedName()} */
  @Override
  String toString();

  ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext);

  @Nullable
  SourcePath getSourcePathToOutput();

  /**
   * @return true if the output of this build rule is compatible with {@code buck build --out}. To
   *     be compatible, that means (1) {@link #getSourcePathToOutput()} cannot return {@code null},
   *     and (2) the output file works as intended when copied to an arbitrary path (i.e., does not
   *     have any dependencies on relative symlinks).
   */
  default boolean outputFileCanBeCopied() {
    return getSourcePathToOutput() != null;
  }

  ProjectFilesystem getProjectFilesystem();

  /**
   * Whether this {@link BuildRule} can be cached.
   *
   * <p>Uncached build rules are never written out to cache, never read from cache, and does not
   * count in cache statistics. This rule is useful for artifacts which cannot be easily normalized.
   *
   * <p>Uncached rules are not always rebuilt, however, as long as the existing on-disk
   * representation is up to date. This means that these rules can take advantage of {@link
   * com.facebook.buck.rules.keys.SupportsInputBasedRuleKey} to prevent rebuilding.
   */
  @JsonIgnore
  boolean isCacheable();

  /**
   * Add additional details when calculating this rule's {@link RuleKey} which isn't available via
   * reflection.
   */
  @SuppressWarnings("unused")
  default void appendToRuleKey(RuleKeyObjectSink sink) {}

  @Override
  default int compareTo(BuildRule that) {
    if (this == that) {
      return 0;
    }

    return this.getBuildTarget().compareTo(that.getBuildTarget());
  }

  default ListenableFuture<Void> buildLocally(
      BuildContext buildContext,
      BuildableContext buildableContext,
      ExecutionContext executionContext,
      StepRunner stepRunner,
      ListeningExecutorService service) {
    return service.submit(
        () -> {
          // Get and run all of the commands.
          List<? extends Step> steps = getBuildSteps(buildContext, buildableContext);

          Optional<BuildTarget> optionalTarget = Optional.of(getBuildTarget());
          for (Step step : steps) {
            stepRunner.runStepForBuildTarget(executionContext, step, optionalTarget);

            // Check for interruptions that may have been ignored by step.
            if (Thread.interrupted()) {
              Thread.currentThread().interrupt();
              throw new InterruptedException();
            }
          }
          return null;
        });
  }
}
