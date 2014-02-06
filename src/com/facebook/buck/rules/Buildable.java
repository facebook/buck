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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Represents something that Buck is able to build, encapsulating the logic of how to determine
 * whether the rule needs to be rebuilt and how to actually go about building the rule itself.
 */
public interface Buildable {

  public BuildableProperties getProperties();

  /**
   * Get the set of input files whose contents should be hashed for the purpose of determining
   * whether this rule is cached.
   * <p>
   * Note that inputs are iterable and should generally be alphabetized so that lists with the same
   * elements will be {@code .equals()} to one another. However, for some build rules (such as
   * {@link com.facebook.buck.shell.Genrule}), the order of the inputs is significant and in these
   * cases the inputs may be ordered in any way the rule feels most appropriate.
   */
  // TODO(simons): Use the Description's constructor arg to generate these.
  public Collection<Path> getInputsToCompareToOutput();

  /**
   * When this method is invoked, all of its dependencies will have been built.
   */
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException;

  // TODO(simons): Base this on the Description constructor arg, but allow optional updates.
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException;

  /**
   * @return the relative path to the primary output of the build rule. If non-null, this path must
   *     identify a single file (as opposed to a directory). If the {@link Buildable} outputs
   *     multiple files, this returns null.
   */
  @Nullable
  public Path getPathToOutputFile();

  /**
   * A {@link Buildable} may employ graph enhancement to alter its dependencies. If it does this, it
   * must return the new dependencies via this method.
   *
   * @param ruleResolver that can be used to get the {@link BuildRule} that corresponds to a
   *     {@link BuildTarget} if the rule for the target has already been constructed.
   * @return The {@code deps} for the {@link BuildRule} that contains this {@link Buildable}. These
   *     may differ from the {@code deps} specified in the original build file due to graph
   *     enhancement. If the {@code deps} are unchanged, then this method should return
   *     {@code null}.
   */
  @Nullable
  public ImmutableSortedSet<BuildRule> getEnhancedDeps(BuildRuleResolver ruleResolver);
}
