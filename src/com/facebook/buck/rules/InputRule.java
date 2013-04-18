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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;

import javax.annotation.Nullable;

/**
 * InputRule is a no-op BuildRule that encapsulates an input file. This encapsulation allows the
 * entire dependency graph to deal with BuildRules, rather than having leaves of a different type.
 */
public class InputRule implements BuildRule {
  private final File inputFile;
  private final BuildTarget buildTarget;
  @Nullable private OutputKey outputKey;
  @Nullable private RuleKey ruleKey;

  /**
   * Convert a set of input file paths to InputRules.
   */
  public static ImmutableSortedSet<InputRule> inputPathsAsInputRules(
      ImmutableSortedSet<String> paths) {
    Comparator<InputRule> comparator = new Comparator<InputRule>() {
      @Override
      public int compare(InputRule o1, InputRule o2) {
        return o1.compareTo(o2);
      }
    };
    ImmutableSortedSet.Builder<InputRule> builder
        = new ImmutableSortedSet.Builder<InputRule>(comparator);
    for (String path : paths) {
      builder.add(new InputRule(path));
    }
    return builder.build();
  }

  public InputRule(File input) {
    inputFile = Preconditions.checkNotNull(input);
    buildTarget = new BuildTarget(input);
  }

  public InputRule(String input) {
    this(new File(input));
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
    return Lists.newArrayList();
  }

  @Override
  public ListenableFuture<BuildRuleSuccess> build(BuildContext context) {
    return Futures.immediateFuture(new BuildRuleSuccess(this));
  }

  @Override
  public boolean isCached(BuildContext context) throws IOException {
    return true;
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
  public File getOutput() {
    return inputFile;
  }

  @Override
  public OutputKey getOutputKey() {
    if (outputKey == null) {
      File outputFile = getOutput();
      if (outputFile != null) {
        outputKey = new OutputKey(outputFile);
      } else {
        outputKey = new OutputKey();
      }
    }
    return outputKey;
  }

  private RuleKey.Builder ruleKeyBuilder() {
    return RuleKey.builder(this)
        .set("fullyQualifiedName", getFullyQualifiedName())
        .set("inputFile", inputFile);
  }

  @Override
  public final RuleKey getRuleKey() {
    if (this.ruleKey != null) {
      return this.ruleKey;
    } else {
      RuleKey ruleKey = ruleKeyBuilder().build();
      // Although this.ruleKey could be null, the RuleKey returned by this method is guaranteed to
      // be non-null.
      this.ruleKey = RuleKey.filter(ruleKey);
      return ruleKey;
    }
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
