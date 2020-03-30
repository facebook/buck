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

package com.facebook.buck.features.zip.rules;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.HasOutputName;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.zip.collect.OnDuplicateEntry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.regex.Pattern;

public class Zip extends ModernBuildRule<Zip> implements HasOutputName, Buildable {
  @AddToRuleKey private final String name;
  @AddToRuleKey private final ImmutableSet<SourcePath> sources;
  @AddToRuleKey private final ImmutableList<SourcePath> zipSources;
  @AddToRuleKey private final OutputPath output;
  @AddToRuleKey private final ImmutableSet<Pattern> entriesToExclude;
  @AddToRuleKey private final OnDuplicateEntry onDuplicateEntry;

  public Zip(
      SourcePathRuleFinder ruleFinder,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      String outputName,
      ImmutableSet<SourcePath> sources,
      ImmutableList<SourcePath> zipSources,
      ImmutableSet<Pattern> entriesToExclude,
      OnDuplicateEntry onDuplicateEntry) {
    super(buildTarget, projectFilesystem, ruleFinder, Zip.class);

    this.name = outputName;
    this.sources = sources;
    this.zipSources = zipSources;
    this.output = new OutputPath(name);
    this.entriesToExclude = entriesToExclude;
    this.onDuplicateEntry = onDuplicateEntry;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    Path outputPath = outputPathResolver.resolvePath(this.output);

    SourcePathResolverAdapter sourcePathResolverAdapter = buildContext.getSourcePathResolver();
    ImmutableMap<Path, Path> entryPathToAbsolutePathMap =
        sourcePathResolverAdapter.createRelativeMap(
            filesystem.resolve(
                getBuildTarget()
                    .getCellRelativeBasePath()
                    .getPath()
                    .toPath(filesystem.getFileSystem())),
            sources);
    return ImmutableList.of(
        new CopyToZipStep(
            filesystem,
            outputPath,
            entryPathToAbsolutePathMap,
            zipSources.stream()
                .map(sourcePathResolverAdapter::getAbsolutePath)
                .collect(ImmutableList.toImmutableList()),
            entriesToExclude,
            onDuplicateEntry));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(output);
  }

  @Override
  public String getOutputName(OutputLabel outputLabel) {
    return name;
  }
}
