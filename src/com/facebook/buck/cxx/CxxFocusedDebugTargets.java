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
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
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
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Build rule for acquiring focused debug targets belonging to this build rule's dependent binary
 * build rule.
 *
 * <p>Under focused debugging, we have a focused targets json file which contains all focused
 * targets. For each binary to be linked, we filter the list of focused targets to grab the targets
 * that belong to each binary. This allows us to update only the affected binaries when the focused
 * targets json file's modified.
 *
 * <p>This rule outputs the intersection of focused targets and the link dependencies of this rule's
 * build target.
 */
public class CxxFocusedDebugTargets extends ModernBuildRule<CxxFocusedDebugTargets.Impl> {
  public static final Flavor FOCUSED_DEBUG_TARGETS = InternalFlavor.of("focused-debug-targets");

  private static final String OUTPUT_FILENAME = "focused_targets.json";

  static class Impl implements Buildable {
    @AddToRuleKey private final OutputPath output;
    @AddToRuleKey private final Optional<SourcePath> focusedTargetsList;
    // The targets representing the static libs to be linked by this build rule's corresponding
    // CxxLink rule
    @AddToRuleKey private final ImmutableList<BuildTarget> linkDependencyTargets;

    Impl(
        BuildTarget buildTarget,
        Optional<SourcePath> focusedTargetsList,
        ImmutableList<BuildTarget> linkDependencyTargets) {
      this.output = new OutputPath(OUTPUT_FILENAME);
      this.focusedTargetsList = focusedTargetsList;

      List<BuildTarget> allTargets = new ArrayList<>(linkDependencyTargets);
      allTargets.add(buildTarget);
      this.linkDependencyTargets = ImmutableList.copyOf(allTargets);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      ImmutableList.Builder<Step> builder = ImmutableList.builder();
      RelPath outputPath = outputPathResolver.resolvePath(output);
      builder.add(
          new GenerateFilteredFocusedDebugTargetsJson(
              filesystem,
              outputPath.getPath(),
              focusedTargetsList.map(buildContext.getSourcePathResolver()::getAbsolutePath),
              linkDependencyTargets));

      return builder.build();
    }
  }

  public CxxFocusedDebugTargets(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Optional<SourcePath> focusedTargetsList,
      ImmutableList<BuildTarget> linkDependencyTargets) {

    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(buildTarget, focusedTargetsList, linkDependencyTargets));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  /** Writes out focused targets included in the list of given targets to a JSON array. */
  static class GenerateFilteredFocusedDebugTargetsJson extends AbstractExecutionStep {
    private final Path outputRelativePath;
    private final ProjectFilesystem filesystem;
    private final ImmutableList<BuildTarget> targets;
    private final Optional<AbsPath> focusedTargetsList;

    public GenerateFilteredFocusedDebugTargetsJson(
        ProjectFilesystem fs,
        Path outputRelativePath,
        Optional<AbsPath> focusedTargetsList,
        ImmutableList<BuildTarget> targets) {
      super("generate " + OUTPUT_FILENAME);
      this.filesystem = fs;
      this.outputRelativePath = outputRelativePath;
      this.focusedTargetsList = focusedTargetsList;
      this.targets = targets;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext context) throws IOException {
      List<BuildTarget> filteredTargets;
      if (focusedTargetsList.isPresent()) {
        Map<String, Object> focusedDict =
            ObjectMappers.READER.readValue(
                ObjectMappers.createParser(focusedTargetsList.get().getPath()),
                new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked")
        List<String> focusedTargets = (List<String>) focusedDict.get("targets");
        Set<String> focusedTargetsSet = new HashSet<>(focusedTargets);

        filteredTargets =
            targets.stream()
                .filter(
                    target ->
                        focusedTargetsSet.contains(target.getUnflavoredBuildTarget().toString()))
                .collect(Collectors.toList());
      } else {
        filteredTargets = targets;
      }

      // Ensure we sort the targets to prevent running into rule key differs.
      filteredTargets = filteredTargets.stream().sorted().collect(Collectors.toList());

      try (OutputStream outputStream = filesystem.newFileOutputStream(outputRelativePath)) {
        try (JsonGenerator jsonGen = ObjectMappers.createGenerator(outputStream)) {
          jsonGen.writeStartArray();
          for (BuildTarget target : filteredTargets) {
            jsonGen.writeString(target.getUnflavoredBuildTarget().toString());
          }
          jsonGen.writeEndArray();
        }
      }

      return StepExecutionResults.SUCCESS;
    }
  }
}
