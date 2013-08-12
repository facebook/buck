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
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SrcsAttributeBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class PythonLibraryRule extends AbstractCachingBuildRule {

  private final ImmutableSortedSet<String> srcs;
  private final Optional<String> pythonPathDirectory;

  protected PythonLibraryRule(BuildRuleParams buildRuleParams,
      ImmutableSortedSet<String> srcs) {
    super(buildRuleParams);
    this.srcs = ImmutableSortedSet.copyOf(srcs);

    if (srcs.isEmpty()) {
      this.pythonPathDirectory = Optional.absent();
    } else {
      this.pythonPathDirectory = Optional.of(getPathToPythonPathDirectory());
    }
  }

  @Override
  public boolean isLibrary() {
    return true;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return super.appendToRuleKey(builder)
        .set("srcs", srcs)
        .set("pythonPathDirectory", pythonPathDirectory);
  }

  private String getPathToPythonPathDirectory() {
    return String.format("%s/%s/__pylib_%s/",
        BuckConstant.BIN_DIR,
        getBuildTarget().getBasePath(),
        getBuildTarget().getShortName());
  }

  public ImmutableSortedSet<String> getPythonSrcs() {
    return srcs;
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    return srcs;
  }

  public Optional<String> getPythonPathDirectory() {
    return pythonPathDirectory;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context) throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Symlink all of the sources to a generated directory so that the generated directory can be
    // included as a $PYTHONPATH element.
    // TODO(mbolin): Do not flatten the directory structure when creating the symlinks, as directory
    // structure is significant in Python when __init__.py is used.
    if (!srcs.isEmpty()) {
      commands.add(new MakeCleanDirectoryStep(getPathToPythonPathDirectory()));
      File pythonPathDirectory = new File(getPathToPythonPathDirectory());
      for (String src : srcs) {
        File target = new File(pythonPathDirectory, new File(src).getName());
        commands.add(new SymlinkFileStep(src, target.getPath()));
      }
    }

    return commands.build();
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.PYTHON_LIBRARY;
  }

  public static Builder newPythonLibraryBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<PythonLibraryRule>
      implements SrcsAttributeBuilder {
    protected ImmutableSortedSet.Builder<String> srcs = ImmutableSortedSet.naturalOrder();

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public Builder addSrc(String src) {
      srcs.add(src);
      return this;
    }

    @Override
    public PythonLibraryRule build(BuildRuleResolver ruleResolver) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);
      return new PythonLibraryRule(buildRuleParams, srcs.build());
    }
  }
}
