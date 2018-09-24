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

package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** A fake {@link BuildRule} that implements {@link SupportsDependencyFileRuleKey}. */
public class FakeDepFileBuildRule extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements SupportsDependencyFileRuleKey {

  private Path outputPath;
  private Predicate<SourcePath> coveredPredicate = (SourcePath path) -> true;
  private Predicate<SourcePath> interestingPredicate = (SourcePath path) -> false;
  private ImmutableList<SourcePath> actualInputPaths = ImmutableList.of();

  public FakeDepFileBuildRule(String fullyQualifiedName) {
    this(BuildTargetFactory.newInstance(fullyQualifiedName));
  }

  public FakeDepFileBuildRule(BuildTarget target) {
    this(target, new FakeProjectFilesystem(), TestBuildRuleParams.create());
  }

  public FakeDepFileBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams) {
    super(buildTarget, projectFilesystem, buildRuleParams);
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
  public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathResolver pathResolver) {
    return coveredPredicate;
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver) {
    return interestingPredicate;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) {
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
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputPath);
  }
}
