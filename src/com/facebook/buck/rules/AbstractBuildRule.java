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
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;

import javax.annotation.Nullable;

abstract class AbstractBuildRule implements BuildRule {

  private final BuildTarget buildTarget;
  private final ImmutableSortedSet<BuildRule> deps;
  private final ImmutableSet<BuildTargetPattern> visibilityPatterns;
  @Nullable private OutputKey outputKey;
  @Nullable private RuleKey ruleKey;

  protected AbstractBuildRule(BuildRuleParams buildRuleParams) {
    Preconditions.checkNotNull(buildRuleParams);
    this.buildTarget = buildRuleParams.getBuildTarget();
    this.deps = buildRuleParams.getDeps();
    this.visibilityPatterns = buildRuleParams.getVisibilityPatterns();

    for (BuildRule dep : this.deps) {
      if (!dep.isVisibleTo(buildTarget)) {
        throw new HumanReadableException("%s depends on %s, which is not visible",
            buildTarget,
            dep);
      }
    }
  }

  @Override
  public abstract BuildRuleType getType();

  @Override
  public final BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public final String getFullyQualifiedName() {
    return buildTarget.getFullyQualifiedName();
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
  public boolean isPackagingRule() {
    return false;
  }

  @Override
  public boolean getExportDeps() {
    return true;
  }

  @Override
  public final ImmutableSortedSet<BuildRule> getDeps() {
    return deps;
  }

  @Override
  public final ImmutableSet<BuildTargetPattern> getVisibilityPatterns() {
    return visibilityPatterns;
  }

  @Override
  public final boolean isVisibleTo(BuildTarget target) {
    // Targets in the same build file are always visible to each other.
    if (target.getBaseName().equals(getBuildTarget().getBaseName())) {
      return true;
    }

    for (BuildTargetPattern pattern : getVisibilityPatterns()) {
      if (pattern.apply(target)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public final int compareTo(BuildRule that) {
    return this.getFullyQualifiedName().compareTo(that.getFullyQualifiedName());
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof AbstractBuildRule)) {
      return false;
    }
    AbstractBuildRule that = (AbstractBuildRule)obj;
    return Objects.equal(this.buildTarget, that.buildTarget);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(this.buildTarget);
  }

  @Override
  public final String toString() {
    return getFullyQualifiedName();
  }

  @Override @Nullable
  public String getPathToOutputFile() {
    return null;
  }

  @Override
  public OutputKey getOutputKey(ProjectFilesystem projectFilesystem) {
    if (this.outputKey != null) {
      return this.outputKey;
    }
    String pathToOutputFile = getPathToOutputFile();
    File outputFile = pathToOutputFile == null
        ? null
        : projectFilesystem.getFileForRelativePath(pathToOutputFile);
    OutputKey outputKey = new OutputKey(outputFile);
    this.outputKey = OutputKey.filter(outputKey);
    return outputKey;
  }

  protected void resetOutputKey() {
    outputKey = null;
  }

  /**
   * {@link #getRuleKey()} and {@link #createRuleKeyWithoutDeps()} uses this when constructing
   * {@link RuleKey}s for this class. Every subclass that extends the rule state in a way that
   * matters to idempotency must override
   * {@link #appendToRuleKey(com.facebook.buck.rules.RuleKey.Builder)} and append its state to the
   * {@link RuleKey.Builder} returned by its superclass's
   * {@link #appendToRuleKey(com.facebook.buck.rules.RuleKey.Builder)} implementation. Example:
   * <pre>
   * &#x40;Override
   * protected RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
   *   return super.appendToRuleKey(builder)
   *       .set("srcs", srcs),
   *       .set("resources", resources);
   * }
   * </pre>
   */
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  /**
   * This method should be overridden only for unit testing.
   */
  @Override
  public RuleKey getRuleKey() {
    if (this.ruleKey != null) {
      return this.ruleKey;
    } else {
      RuleKey.Builder builder = RuleKey.builder(this);
      appendToRuleKey(builder);
      RuleKey ruleKey = builder.build();
      // Although this.ruleKey could be null, the RuleKey returned by this method is guaranteed to
      // be non-null.
      this.ruleKey = RuleKey.filter(ruleKey);
      return ruleKey;
    }
  }

  /**
   * Creates a new {@link RuleKey} for this {@link BuildRule} that does not take {@link #getDeps()}
   * into account.
   *
   * @see AbiRule#getRuleKeyWithoutDeps()
   */
  protected RuleKey createRuleKeyWithoutDeps() {
    return appendToRuleKey(RuleKey.builderWithoutDeps(this)).build();
  }

  /**
   * @return Whether the input path directs to a file in the buck generated files folder.
   */
  public static boolean isGeneratedFile(String pathRelativeToProjectRoot) {
    return pathRelativeToProjectRoot.startsWith(BuckConstant.GEN_DIR);
  }
}
