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

package com.facebook.buck.jvm.scala;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavacSpec;
import com.facebook.buck.jvm.java.toolchain.JavaToolchain;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;

public class FauxScalaLibraryBuilder
    extends AbstractNodeBuilder<
        ScalaLibraryDescriptionArg.Builder,
        ScalaLibraryDescriptionArg,
        ScalaLibraryDescription,
        BuildRule> {

  private final ProjectFilesystem projectFilesystem;

  private FauxScalaLibraryBuilder(
      BuildTarget target, ProjectFilesystem projectFilesystem, ScalaBuckConfig scalaBuckConfig) {
    super(
        new ScalaLibraryDescription(
            new ToolchainProviderBuilder()
                .withToolchain(
                    JavacOptionsProvider.DEFAULT_NAME,
                    JavacOptionsProvider.of(DEFAULT_JAVAC_OPTIONS))
                .withToolchain(
                    JavaToolchain.DEFAULT_NAME,
                    JavaToolchain.of(JavacSpec.builder().build().getJavacProvider()))
                .build(),
            scalaBuckConfig,
            null),
        target,
        projectFilesystem);
    this.projectFilesystem = projectFilesystem;
  }

  public static FauxScalaLibraryBuilder createBuilder(BuildTarget target) {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "scala",
                    ImmutableMap.of(
                        "compiler", "scala-compiler.jar",
                        "library", "//:scala-library")))
            .build();
    ScalaBuckConfig scalaBuckConfig = new ScalaBuckConfig(buckConfig);
    return new FauxScalaLibraryBuilder(target, new FakeProjectFilesystem(), scalaBuckConfig);
  }

  public FauxScalaLibraryBuilder addSrc(SourcePath path) {
    getArgForPopulating().addSrcs(path);
    return this;
  }

  public FauxScalaLibraryBuilder addSrc(Path path) {
    return addSrc(PathSourcePath.of(projectFilesystem, path));
  }
}
