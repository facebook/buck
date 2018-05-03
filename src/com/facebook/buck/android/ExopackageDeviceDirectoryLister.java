/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.InstallTrigger;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This lists the entire contents of the exopackage installation directory on the requested devices.
 *
 * <p>While only the package-specific directory's contents are required, this rule lists everything
 * so that it doesn't need to depend on the manifest.
 */
public class ExopackageDeviceDirectoryLister extends AbstractBuildRule {
  @AddToRuleKey private final InstallTrigger trigger;
  private final Path outputPath;

  public ExopackageDeviceDirectoryLister(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
    super(buildTarget, projectFilesystem);
    trigger = new InstallTrigger(projectFilesystem);
    outputPath = BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s/exo.contents");
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    return ImmutableList.of(
        new AbstractExecutionStep("listing_exo_contents") {
          @Override
          public StepExecutionResult execute(ExecutionContext context)
              throws IOException, InterruptedException {
            trigger.verify(context);
            ConcurrentHashMap<String, SortedSet<String>> contents = new ConcurrentHashMap<>();
            context
                .getAndroidDevicesHelper()
                .get()
                .adbCallOrThrow(
                    "listing_exo_contents_for_device",
                    (device) -> {
                      device.mkDirP(ExopackageInstaller.EXOPACKAGE_INSTALL_ROOT.toString());
                      contents.put(
                          device.getSerialNumber(),
                          device
                              .listDirRecursive(ExopackageInstaller.EXOPACKAGE_INSTALL_ROOT)
                              .stream()
                              .map(Path::toString)
                              .collect(
                                  ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
                      return true;
                    },
                    true);
            getProjectFilesystem().mkdirs(outputPath.getParent());
            getProjectFilesystem()
                .writeContentsToPath(
                    serializeDirectoryContents(ImmutableSortedMap.copyOf(contents)), outputPath);
            buildableContext.recordArtifact(outputPath);
            return StepExecutionResults.SUCCESS;
          }
        });
  }

  @VisibleForTesting
  static String serializeDirectoryContents(SortedMap<String, SortedSet<String>> contents)
      throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(contents);
  }

  static ImmutableSortedMap<String, ImmutableSortedSet<Path>>
      deserializeDirectoryContentsForPackage(
          ProjectFilesystem filesystem, Path contentsPath, String packageName) throws IOException {
    TypeReference<Map<String, SortedSet<String>>> typeRef =
        new TypeReference<Map<String, SortedSet<String>>>() {};
    String json = filesystem.readFileIfItExists(contentsPath).get();
    Path packagePath = Paths.get(packageName);
    Map<String, SortedSet<String>> parsedJson = new ObjectMapper().readValue(json, typeRef);
    return parsedJson
        .entrySet()
        .stream()
        .collect(
            ImmutableSortedMap.toImmutableSortedMap(
                Ordering.natural(),
                Map.Entry::getKey,
                entry ->
                    entry
                        .getValue()
                        .stream()
                        .map(Paths::get)
                        .filter(p -> p.startsWith(packagePath))
                        .map(packagePath::relativize)
                        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()))));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputPath);
  }

  @Override
  public boolean isCacheable() {
    return false;
  }
}
