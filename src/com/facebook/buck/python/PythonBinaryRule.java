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

package com.facebook.buck.python;

import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.util.List;

public class PythonBinaryRule extends AbstractCachingBuildRule implements BinaryBuildRule {

  private final String main;

  protected PythonBinaryRule(BuildRuleParams buildRuleParams, String main) {
    super(buildRuleParams);
    this.main = Preconditions.checkNotNull(main);
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    return super.ruleKeyBuilder()
        .set("main", main);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.PYTHON_BINARY;
  }

  @Override
  public String getExecutableCommand() {
    String pythonPath = Joiner.on(':').join(Iterables.transform(getPythonPathEntries(),
        Functions.RELATIVE_TO_ABSOLUTE_PATH));
    return String.format("PYTHONPATH=%s python %s",
        pythonPath,
        Functions.RELATIVE_TO_ABSOLUTE_PATH.apply(main));
  }

  private ImmutableSet<String> getPythonPathEntries() {
    final ImmutableSet.Builder<String> entries = ImmutableSet.builder();

    final PythonBinaryRule pythonBinaryRule = this;
    new AbstractDependencyVisitor(this) {

      @Override
      public boolean visit(BuildRule rule) {
        if (rule instanceof PythonLibraryRule) {
          PythonLibraryRule pythonLibraryRule = (PythonLibraryRule)rule;

          Optional<String> pythonPathEntry = pythonLibraryRule.getPythonPathDirectory();
          if (pythonPathEntry.isPresent()) {
            entries.add(pythonPathEntry.get());
          }
          return true;
        }

        // AbstractDependencyVisitor will start from this (PythonBinaryRule) so make sure it
        // descends to its dependencies even though it is not a library rule.
        return rule == pythonBinaryRule;
      }

    }.start();

    return entries.build();
  }

  @Override
  protected Iterable<String> getInputsToCompareToOutput(BuildContext context) {
    if (main != null) {
      return ImmutableList.of(main);
    } else {
      return ImmutableList.of();
    }
  }

  @Override
  protected List<Step> buildInternal(BuildContext context)
      throws IOException {
    // TODO(mbolin): Package Python code, if appropriate. There does not appear to be a standard
    // cross-platform way to do this.
    return ImmutableList.of();
  }

  @Override
  public boolean isPackagingRule() {
    return true;
  }

  public static Builder newPythonBinaryBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractBuildRuleBuilder<PythonBinaryRule> {

    private String main;

    private Builder() {}

    @Override
    public PythonBinaryRule build(BuildRuleResolver ruleResolver) {
      return new PythonBinaryRule(createBuildRuleParams(ruleResolver), main);
    }

    public Builder setMain(String main) {
      this.main = main;
      return this;
    }
  }
}
