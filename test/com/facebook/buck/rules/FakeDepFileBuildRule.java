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
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** A fake {@link BuildRule} that implements {@link SupportsDependencyFileRuleKey}. */
public class FakeDepFileBuildRule extends AbstractBuildRule
    implements SupportsDependencyFileRuleKey {

  private Path outputPath;
  private Predicate<SourcePath> coveredPredicate = (SourcePath path) -> true;
  private Predicate<SourcePath> interestingPredicate = (SourcePath path) -> false;
  private ImmutableList<SourcePath> actualInputPaths = ImmutableList.of();

  public FakeDepFileBuildRule(String fullyQualifiedName) {
    this(BuildTargetFactory.newInstance(fullyQualifiedName));
  }

  public FakeDepFileBuildRule(BuildTarget target) {
    this(
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(new FakeProjectFilesystem())
            .build());
  }

  public FakeDepFileBuildRule(BuildRuleParams buildRuleParams) {
    super(buildRuleParams);
  }

  public FakeDepFileBuildRule setOutputPath(Path outputPath) {
    this.outputPath = outputPath;
    return this;
  }

  public FakeDepFileBuildRule setCoveredByDepFilePredicate(ImmutableSet<SourcePath> coveredPaths) {
    return setCoveredByDepFilePredicate(coveredPaths::contains);
  }

  public FakeDepFileBuildRule setCoveredByDepFilePredicate(Predicate<SourcePath> coveredPredicate) {
    this.coveredPredicate = coveredPredicate;
    return this;
  }

  public FakeDepFileBuildRule setExistenceOfInterestPredicate(
      ImmutableSet<SourcePath> interestingPaths) {
    return setExistenceOfInterestPredicate(interestingPaths::contains);
  }

  public FakeDepFileBuildRule setExistenceOfInterestPredicate(
      Predicate<SourcePath> interestingPredicate) {
    this.interestingPredicate = interestingPredicate;
    return this;
  }

  public FakeDepFileBuildRule setInputsAfterBuildingLocally(
      ImmutableList<SourcePath> actualInputPaths) {
    this.actualInputPaths = actualInputPaths;
    return this;
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return true;
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate() {
    return coveredPredicate;
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate() {
    return interestingPredicate;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context) {
    return actualInputPaths;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    if (outputPath == null) {
      return null;
    }
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), outputPath);
  }
}
