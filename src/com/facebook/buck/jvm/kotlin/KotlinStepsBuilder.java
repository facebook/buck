/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.cd.model.kotlin.BaseCommandParams;
import com.facebook.buck.cd.model.kotlin.BuildKotlinCommand;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.jvm.cd.CompileStepsBuilder;
import com.facebook.buck.jvm.cd.DefaultCompileStepsBuilderFactory;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;

/** Converts serialized protobuf commands into steps to compile Kotlin targets. */
public class KotlinStepsBuilder {
  private final ImmutableList<IsolatedStep> steps;
  private final AbsPath ruleCellRoot;

  public KotlinStepsBuilder(BuildKotlinCommand buildKotlinCommand) {
    Pair<AbsPath, ImmutableList<IsolatedStep>> pair = buildSteps(buildKotlinCommand);
    ruleCellRoot = pair.getFirst();
    steps = pair.getSecond();
  }

  /** @return the steps corresponding to the protobuf command */
  public ImmutableList<IsolatedStep> getSteps() {
    return steps;
  }

  /** @return the rule cell root. */
  public AbsPath getRuleCellRoot() {
    return ruleCellRoot;
  }

  private Pair<AbsPath, ImmutableList<IsolatedStep>> buildSteps(
      BuildKotlinCommand buildKotlinCommand) {
    BaseCommandParams baseCommandParams = buildKotlinCommand.getBaseCommandParams();
    DaemonKotlincToJarStepFactory kotlincToJarStepFactory =
        new DaemonKotlincToJarStepFactory(
            baseCommandParams.getHasAnnotationProcessing(), baseCommandParams.getWithDownwardApi());

    DefaultCompileStepsBuilderFactory<KotlinExtraParams> stepsBuilderFactory =
        new DefaultCompileStepsBuilderFactory<>(kotlincToJarStepFactory);

    AbsPath ruleCellRoot = null;
    CompileStepsBuilder compileStepsBuilder;

    switch (buildKotlinCommand.getCommandCase()) {
      case LIBRARYJARCOMMAND:
        compileStepsBuilder = stepsBuilderFactory.getLibraryBuilder();
        break;

      case ABIJARCOMMAND:
        compileStepsBuilder = stepsBuilderFactory.getAbiBuilder();
        break;

      case COMMAND_NOT_SET:
      default:
        throw new IllegalStateException(buildKotlinCommand.getCommandCase() + " is not supported!");
    }

    return new Pair<>(ruleCellRoot, compileStepsBuilder.buildIsolatedSteps());
  }
}
