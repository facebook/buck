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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;

import javax.annotation.Nullable;

/**
 * InputRule is a no-op BuildRule that encapsulates an input file. This encapsulation allows the
 * entire dependency graph to deal with BuildRules, rather than having leaves of a different type.
 */
public class InputRule implements BuildRule {

  private static final BuildRuleSuccess.Type SUCCESS_TYPE = BuildRuleSuccess.Type.BY_DEFINITION;

  private final File inputFile;
  private final String relativePath;
  private final BuildTarget buildTarget;
  private final ListenableFuture<BuildRuleSuccess> buildOutput;
  @Nullable private RuleKey ruleKey;

  /**
   * It is imperative that {@code inputFile} be properly relativized, so use
   * {@link #inputPathAsInputRule(String, Function)} to create an {@link InputRule}. The only reason
   * this method is {@code protected} rather than {@code private} is so that fakes can be created
   * for testing.
   */
  @VisibleForTesting
  protected InputRule(File inputFile, String relativePath) {
    this.inputFile = Preconditions.checkNotNull(inputFile);
    this.relativePath = Preconditions.checkNotNull(relativePath);
    this.buildTarget = BuildTarget.createBuildTargetForInputFile(inputFile, relativePath);
    this.buildOutput = Futures.immediateFuture(new BuildRuleSuccess(this, SUCCESS_TYPE));
  }

  public static InputRule inputPathAsInputRule(String relativePath,
      Function<String, String> pathRelativizer) {
    return Iterables.getOnlyElement(
        inputPathsAsInputRules(ImmutableList.of(relativePath), pathRelativizer));
  }

  /**
   * Convert a set of input file paths to InputRules.
   */
  public static ImmutableSortedSet<InputRule> inputPathsAsInputRules(Iterable<String> paths,
      Function<String, String> pathRelativizer) {
    ImmutableSortedSet.Builder<InputRule> builder = ImmutableSortedSet.naturalOrder();
    for (String path : paths) {
      builder.add(new InputRule(new File(pathRelativizer.apply(path)), path));
    }
    return builder.build();
  }

  @Override
  public BuildRuleSuccess.Type getBuildResultType() {
    return SUCCESS_TYPE;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public String getFullyQualifiedName() {
    return buildTarget.getFullyQualifiedName();
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.INPUT;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSet<BuildTargetPattern> getVisibilityPatterns() {
    return ImmutableSet.of(BuildTargetPattern.MATCH_ALL);
  }

  @Override
  public boolean isVisibleTo(BuildTarget target) {
    return true;
  }

  @Override
  public Iterable<InputRule> getInputs() {
    return ImmutableList.of();
  }

  @Override
  public ListenableFuture<BuildRuleSuccess> build(BuildContext context) {
    return buildOutput;
  }

  @Override
  public boolean isAndroidRule() {
    return false;
  }

  @Override
  public boolean isLibrary() {
    return false;
  }

  @Override
  public boolean getExportDeps() {
    return false;
  }

  @Override
  public boolean isPackagingRule() {
    return false;
  }

  @Override
  public String getPathToOutputFile() {
    return relativePath;
  }

  @Override
  public RuleKey getRuleKey() {
    if (this.ruleKey == null) {
      ruleKey = RuleKey.builder(this).set("inputFile", inputFile).build();
    }
    return ruleKey;
  }

  @Override
  public int hashCode() {
    return getFullyQualifiedName().hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other == null || getClass() != other.getClass()) {
      return false;
    }
    return (compareTo((InputRule) other) == 0);
  }

  @Override
  public int compareTo(BuildRule buildRule) {
    return getFullyQualifiedName().compareTo(buildRule.getFullyQualifiedName());
  }

  @Override
  public String toString() {
    return getFullyQualifiedName();
  }
}
