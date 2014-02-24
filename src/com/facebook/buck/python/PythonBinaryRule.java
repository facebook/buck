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

import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public class PythonBinaryRule extends DoNotUseAbstractBuildable implements BinaryBuildRule {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(PACKAGING);

  private final Path main;

  protected PythonBinaryRule(BuildRuleParams buildRuleParams, Path main) {
    super(buildRuleParams);
    this.main = Preconditions.checkNotNull(main);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.PYTHON_BINARY;
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public List<String> getExecutableCommand(ProjectFilesystem projectFilesystem) {
    String pythonPath = Joiner.on(':').join(
        Iterables.transform(
            getPythonPathEntries(),
            projectFilesystem.getAbsolutifier()));
    return ImmutableList.of(String.format("PYTHONPATH=%s", pythonPath), "python",
        projectFilesystem.getAbsolutifier().apply(main).toString());
  }

  @VisibleForTesting
  ImmutableSet<Path> getPythonPathEntries() {
    final ImmutableSet.Builder<Path> entries = ImmutableSet.builder();

    final PythonBinaryRule pythonBinaryRule = this;
    new AbstractDependencyVisitor(this) {

      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        Buildable buildable = rule.getBuildable();
        if (buildable instanceof PythonLibrary) {
          PythonLibrary pythonLibrary = (PythonLibrary) buildable;

          Path pythonPathEntry = pythonLibrary.getPythonPathDirectory();
          entries.add(pythonPathEntry);
          return rule.getDeps();
        }

        // AbstractDependencyVisitor will start from this (PythonBinaryRule) so make sure it
        // descends to its dependencies even though it is not a library rule.
        return maybeVisitAllDeps(rule, rule == pythonBinaryRule);
      }

    }.start();

    return entries.build();
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    if (main != null) {
      return ImmutableList.of(main);
    } else {
      return ImmutableList.of();
    }
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    // TODO(mbolin): Package Python code, if appropriate. There does not appear to be a standard
    // cross-platform way to do this.
    return ImmutableList.of();
  }

  public static Builder newPythonBinaryBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<PythonBinaryRule> {

    private Path main;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public PythonBinaryRule build(BuildRuleResolver ruleResolver) {
      return new PythonBinaryRule(createBuildRuleParams(ruleResolver), main);
    }

    public Builder setMain(Path main) {
      this.main = main;
      return this;
    }
  }
}
