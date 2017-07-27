/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableCollection;
import org.immutables.value.Value;

public class ScalaLibraryDescription
    implements Description<ScalaLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<
            ScalaLibraryDescription.AbstractScalaLibraryDescriptionArg> {

  private final ScalaBuckConfig scalaBuckConfig;

  public ScalaLibraryDescription(ScalaBuckConfig scalaBuckConfig) {
    this.scalaBuckConfig = scalaBuckConfig;
  }

  @Override
  public Class<ScalaLibraryDescriptionArg> getConstructorArgType() {
    return ScalaLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams rawParams,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      ScalaLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    ScalaLibraryBuilder scalaLibraryBuilder =
        new ScalaLibraryBuilder(
                targetGraph,
                buildTarget,
                projectFilesystem,
                rawParams,
                resolver,
                cellRoots,
                scalaBuckConfig)
            .setArgs(args);

    return HasJavaAbi.isAbiTarget(buildTarget)
        ? scalaLibraryBuilder.buildAbi()
        : scalaLibraryBuilder.build();
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractScalaLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder
        .add(scalaBuckConfig.getScalaLibraryTarget())
        .addAll(scalaBuckConfig.getCompilerPlugins());
    Optionals.addIfPresent(scalaBuckConfig.getScalacTarget(), extraDepsBuilder);
  }

  public interface CoreArg extends JavaLibraryDescription.CoreArg {
    ImmutableList<String> getExtraArguments();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractScalaLibraryDescriptionArg extends CoreArg {}
}
