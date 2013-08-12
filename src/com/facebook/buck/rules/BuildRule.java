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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;

import javax.annotation.Nullable;

public interface BuildRule extends Comparable<BuildRule> {

  public static final String VISIBILITY_PUBLIC = "PUBLIC";

  public BuildTarget getBuildTarget();

  public String getFullyQualifiedName();

  public BuildRuleType getType();

  /**
   * @return the value of the "deps" attribute for this build rule
   */
  public ImmutableSortedSet<BuildRule> getDeps();

  /**
   * @return the value of the "visibility" attribute for this build rule
   */
  public ImmutableSet<BuildTargetPattern> getVisibilityPatterns();

  /**
   * @return whether this build rule is visible to the build target or not
   */
  public boolean isVisibleTo(BuildTarget target);

  /**
   * @return the inputs needed to build this build rule
   */
  public Iterable<InputRule> getInputs();

  /**
   * This method must be idempotent.
   */
  public ListenableFuture<BuildRuleSuccess> build(BuildContext context);

  /**
   * Returns the way in which this rule was built.
   * <p>
   * <strong>IMPORTANT:</strong> This rule must have finished building before this method is
   * invoked.
   */
  public BuildRuleSuccess.Type getBuildResultType();

  /**
   * @return whether this rule exists only in an Android project.
   */
  public boolean isAndroidRule();

  public boolean isLibrary();

  /**
   * @return whether or not this rule is considered a packaging rule.  Packaging rules
   *   (like java_binary) are rules that package up all of their transitive dependencies in their
   *   final output.
   */
  public boolean isPackagingRule();

  /**
   * @return whether or not this rule exports its dependencies to all rules depending on it.
   */
  public boolean getExportDeps();

  /**
   * @return the relative path to the primary output of the build rule. If non-null, this path must
   *     identify a single file (as opposed to a directory).
   */
  @Nullable
  public String getPathToOutputFile();

  /**
   * @return key based on the BuildRule's state, including the transitive closure of its
   *     dependencies' keys.
   */
  public RuleKey getRuleKey() throws IOException;

  /** @return the same value as {@link #getFullyQualifiedName()} */
  @Override
  public String toString();
}
