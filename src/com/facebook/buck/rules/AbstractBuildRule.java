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
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
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
        throw new HumanReadableException(String.format("%s depends on %s, which is not visible",
            buildTarget, dep));
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
    return this instanceof JavaLibraryRule ||
        this instanceof AndroidResourceRule;
  }

  @Override
  public final ImmutableSortedSet<BuildRule> getDeps() {
    return deps;
  }

  /**
   * Include the classpath entries from all JavaLibraryRules that have a direct line of lineage
   * to this rule through other JavaLibraryRules. For example, in the following dependency graph:
   *
   *        A
   *      /   \
   *     B     C
   *    / \   / \
   *    D E   F G
   *
   * If all of the nodes correspond to BuildRules that implement JavaLibraryRule except for
   * B (suppose B is a Genrule), then A's classpath will include C, F, and G, but not D and E.
   * This is because D and E are used to generate B, but do not contribute .class files to things
   * that depend on B. However, if C depended on E as well as F and G, then E would be included in
   * A's classpath.
   */
  protected final ImmutableSetMultimap<BuildRule, String> getClasspathEntriesForDeps() {
    final ImmutableSetMultimap.Builder<BuildRule, String> classpathEntries =
        ImmutableSetMultimap.builder();
    for (BuildRule dep : getDeps()) {
      if (dep instanceof JavaLibraryRule) {
        JavaLibraryRule libraryRule = (JavaLibraryRule)dep;
        classpathEntries.putAll(libraryRule.getClasspathEntriesMap());
      }
    }
    return classpathEntries.build();
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
  public File getOutput() {
    return null;
  }

  @Override
  public OutputKey getOutputKey() {
    if (outputKey == null) {
      File outputFile = getOutput();
      // Take care to generate OutputKeys only for files!
      if (outputFile != null && outputFile.isFile()) {
        outputKey = new OutputKey(outputFile);
      } else {
        outputKey = new OutputKey();
      }
    }
    return outputKey;
  }

  protected void resetOutputKey() {
    outputKey = null;
  }

  /**
   * getRuleKey() calls the most derived implementation of this method to lazily construct a
   * RuleKey. Every subclass that extends the rule state in a way that matters to idempotency must
   * override ruleKeyBuilder() and append its state to the RuleKey.Builder returned by its
   * superclass's ruleKeyBuilder() implementation.
   */
  protected RuleKey.Builder ruleKeyBuilder() {
    return RuleKey.builder(this);
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

  /**
   * @return Whether the input path directs to a file in the buck generated files folder.
   */
  public static boolean isGeneratedFile(String pathRelativeToProjectRoot) {
    return pathRelativeToProjectRoot.startsWith(BuckConstant.GEN_DIR);
  }
}
