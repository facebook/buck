/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.parser;

import com.facebook.buck.core.files.DirectoryListComputation;
import com.facebook.buck.core.files.FileTreeComputation;
import com.facebook.buck.core.graph.transformation.impl.GraphComputationStage;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.environment.PlatformType;
import com.google.common.collect.ImmutableList;
import junitparams.JUnitParamsRunner;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class BuildTargetPatternToBuildPackagePathTransformerTest
    extends AbstractBuildPackageComputationTest {
  @Override
  protected boolean isBuildFileCaseSensitive() {
    // TODO: Figure out why Windows behaves differently from Linux and macOS.
    return Platform.detect().getType() != PlatformType.WINDOWS;
  }

  @Override
  protected ImmutableList<GraphComputationStage<?, ?>> getComputationStages(String buildFileName) {
    return ImmutableList.of(
        new GraphComputationStage<>(
            BuildTargetPatternToBuildPackagePathComputation.of(buildFileName, filesystem.asView())),
        new GraphComputationStage<>(DirectoryListComputation.of(filesystem.asView())),
        new GraphComputationStage<>(FileTreeComputation.of()));
  }
}
