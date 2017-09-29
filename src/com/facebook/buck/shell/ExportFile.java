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

package com.facebook.buck.shell;

import com.facebook.buck.event.EventDispatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.InputDataRetriever;
import com.facebook.buck.rules.modern.InputPath;
import com.facebook.buck.rules.modern.InputPathResolver;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/**
 * Export a file so that it can be easily referenced by other {@link
 * com.facebook.buck.rules.BuildRule}s. There are several valid ways of using export_file (all
 * examples in a build file located at "path/to/buck/BUCK").
 *
 * <p>The most common usage of export_file is:
 *
 * <pre>
 *   export_file(name = 'some-file.html')
 * </pre>
 *
 * This is equivalent to:
 *
 * <pre>
 *   export_file(name = 'some-file.html',
 *     src = 'some-file.html',
 *     out = 'some-file.html')
 * </pre>
 *
 * This results in "//path/to/buck:some-file.html" as the rule, and will export the file
 * "some-file.html" as "some-file.html".
 *
 * <pre>
 *   export_file(
 *     name = 'foobar.html',
 *     src = 'some-file.html',
 *   )
 * </pre>
 *
 * Is equivalent to:
 *
 * <pre>
 *    export_file(name = 'foobar.html', src = 'some-file.html', out = 'foobar.html')
 * </pre>
 *
 * Finally, it's possible to refer to the exported file with a logical name, while controlling the
 * actual file name. For example:
 *
 * <pre>
 *   export_file(name = 'ie-exports',
 *     src = 'some-file.js',
 *     out = 'some-file-ie.js',
 *   )
 * </pre>
 *
 * As a rule of thumb, if the "out" parameter is missing, the "name" parameter is used as the name
 * of the file to be saved.
 */
// TODO(simons): Extend to also allow exporting a rule.
public class ExportFile extends ModernBuildRule<ExportFile>
    implements Buildable, HasOutputName, HasRuntimeDeps {

  private final String name;
  private final ExportFileDescription.Mode mode;
  private final InputPath src;
  private final OutputPath out;

  public ExportFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      String name,
      ExportFileDescription.Mode mode,
      InputPath src) {
    super(buildTarget, projectFilesystem, ruleFinder, ExportFile.class);
    this.name = name;
    this.mode = mode;
    this.src = src;
    this.out = new OutputPath(projectFilesystem.getPath(name));
  }

  @VisibleForTesting
  InputPath getSource() {
    return src;
  }

  private OutputPath getCopiedPath() {
    Preconditions.checkState(mode == ExportFileDescription.Mode.COPY);
    return out;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      EventDispatcher eventDispatcher,
      ProjectFilesystem filesystem,
      InputPathResolver inputPathResolver,
      InputDataRetriever inputDataRetriever,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {

    // This file is copied rather than symlinked so that when it is included in an archive zip and
    // unpacked on another machine, it is an ordinary file in both scenarios.
    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    if (mode == ExportFileDescription.Mode.COPY) {

      builder.add(RmStep.of(buildCellPathFactory.from(getCopiedPath())).withRecursive(true));

      Path path = inputPathResolver.resolvePath(src);
      Path destination = outputPathResolver.resolvePath(getCopiedPath());

      builder.add(MkdirStep.of(buildCellPathFactory.from(destination.getParent())));

      if (filesystem.isDirectory(path)) {
        builder.add(
            CopyStep.forDirectory(
                filesystem,
                path,
                destination,
                CopyStep.DirectoryMode.CONTENTS_ONLY));
      } else {
        builder.add(CopyStep.forFile(filesystem, path, destination));
      }
    }

    return builder.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    // In reference mode, we just return the relative path to the source, as we've already verified
    // that our filesystem matches that of the source.  In copy mode, we return the path we've
    // allocated for the copy.
    return mode == ExportFileDescription.Mode.REFERENCE
        ? src.getLimitedSourcePath()
        : getSourcePath(getCopiedPath());
  }

  @Override
  public String getOutputName() {
    return name;
  }
}
