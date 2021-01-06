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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.HasOutputName;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.FakeActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class ResourcesParametersTest {
  @Test
  public void canGetOutputNameFromHasOutputName() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//android/java:resources");
    OutputLabel outputLabel = OutputLabel.of("label");
    FakeBuildRuleWithOutputName rule =
        new FakeBuildRuleWithOutputName(
            buildTarget,
            ImmutableMap.of(outputLabel, "label", OutputLabel.defaultLabel(), "default"));
    SourcePathRuleFinder ruleFinder =
        new FakeActionGraphBuilder(ImmutableMap.of(buildTarget, rule));
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    SourcePath pathWithDefaultOutputLabel =
        ExplicitBuildTargetSourcePath.of(buildTarget, Paths.get("default"));
    SourcePath pathWithNamedOutputLabel =
        ExplicitBuildTargetSourcePath.of(
            BuildTargetWithOutputs.of(buildTarget, outputLabel),
            Paths.get("explicit_path_with_label"));

    ImmutableSortedMap<String, SourcePath> actual =
        ResourcesParameters.getNamedResources(
            ruleFinder,
            projectFilesystem,
            ImmutableSortedSet.of(pathWithDefaultOutputLabel, pathWithNamedOutputLabel));
    assertEquals(
        ImmutableSortedMap.of(
                PathFormatter.pathWithUnixSeparators(
                        getBasePath(buildTarget, projectFilesystem.getFileSystem())
                            .resolve("default")),
                    pathWithDefaultOutputLabel,
                PathFormatter.pathWithUnixSeparators(
                        getBasePath(buildTarget, projectFilesystem.getFileSystem())
                            .resolve("label")),
                    pathWithNamedOutputLabel)
            .entrySet(),
        actual.entrySet());
  }

  private Path getBasePath(BuildTarget buildTarget, FileSystem fileSystem) {
    return buildTarget.getCellRelativeBasePath().getPath().toPath(fileSystem);
  }

  private static class FakeBuildRuleWithOutputName extends FakeBuildRule implements HasOutputName {
    private final ImmutableMap<OutputLabel, String> outputLabelToOutputName;

    public FakeBuildRuleWithOutputName(
        BuildTarget buildTarget, ImmutableMap<OutputLabel, String> outputLabelToOutputName) {
      super(buildTarget);
      this.outputLabelToOutputName = outputLabelToOutputName;
    }

    @Override
    public String getOutputName(OutputLabel outputLabel) {
      String outputName = outputLabelToOutputName.get(outputLabel);
      if (outputName == null) {
        fail();
      }
      return outputName;
    }
  }
}
