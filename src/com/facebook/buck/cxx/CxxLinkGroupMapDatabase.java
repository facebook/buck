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

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;

/** Exposes which build targets are included in a link group via flavor. */
public class CxxLinkGroupMapDatabase extends ModernBuildRule<CxxLinkGroupMapDatabase.Impl> {
  private static final Logger LOG = Logger.get(CxxLinkGroupMapDatabase.class);
  public static final Flavor LINK_GROUP_MAP_DATABASE = InternalFlavor.of("link-group-map-database");

  private static final String OUTPUT_FILENAME = "link_group_map.json";

  /** Internal buildable implementation */
  static class Impl implements Buildable {
    @AddToRuleKey private final OutputPath output;
    private final Collection<BuildTarget> targets;

    Impl(Collection<BuildTarget> targets) {
      this.output = new OutputPath(OUTPUT_FILENAME);
      this.targets = targets;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      ImmutableList.Builder<Step> builder = ImmutableList.builder();
      Path outputPath = outputPathResolver.resolvePath(output);
      builder
          .add(MkdirStep.of(buildCellPathFactory.from(outputPath.getParent())))
          .add(new GenerateLinkGroupMapJson(filesystem, outputPath, targets));

      return builder.build();
    }
  }

  CxxLinkGroupMapDatabase(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Collection<BuildTarget> targets) {
    super(buildTarget, projectFilesystem, ruleFinder, new Impl(targets));

    LOG.debug("Creating link group map database %s", buildTarget);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  /** Writes out targets included in a link group as a JSON array. */
  static class GenerateLinkGroupMapJson extends AbstractExecutionStep {
    private final Path outputRelativePath;
    private final ProjectFilesystem filesystem;
    private final Collection<BuildTarget> targets;

    public GenerateLinkGroupMapJson(
        ProjectFilesystem fs, Path outputRelativePath, Collection<BuildTarget> targets) {
      super("generate " + OUTPUT_FILENAME);
      this.filesystem = fs;
      this.outputRelativePath = outputRelativePath;
      this.targets = targets;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) throws IOException {
      try (OutputStream outputStream = filesystem.newFileOutputStream(outputRelativePath)) {
        try (JsonGenerator jsonGen = ObjectMappers.createGenerator(outputStream)) {
          jsonGen.writeStartArray();
          for (BuildTarget target : targets) {
            jsonGen.writeString(target.toString());
          }
          jsonGen.writeEndArray();
        }
      }

      return StepExecutionResults.SUCCESS;
    }
  }
}
