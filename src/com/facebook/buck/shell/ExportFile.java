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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.HasOutputName;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ExportFileDescription.Mode;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;
import java.util.stream.Stream;

/**
 * Export a file so that it can be easily referenced by other {@link BuildRule}s. There are several
 * valid ways of using export_file (all examples in a build file located at "path/to/buck/BUCK").
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
public class ExportFile extends AbstractBuildRule
    implements HasOutputName, HasRuntimeDeps, SupportsInputBasedRuleKey {

  @AddToRuleKey private final String name;
  @AddToRuleKey private final ExportFileDescription.Mode mode;
  @AddToRuleKey private final SourcePath src;
  @AddToRuleKey private final ExportFileDirectoryAction directoryAction;

  private final ImmutableSortedSet<BuildRule> buildDeps;

  public ExportFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      String name,
      Mode mode,
      SourcePath src,
      ExportFileDirectoryAction directoryAction) {
    super(buildTarget, projectFilesystem);
    this.name = name;
    this.mode = mode;
    this.src = src;
    this.directoryAction = directoryAction;
    this.buildDeps =
        ruleFinder.getRule(src).map(ImmutableSortedSet::of).orElse(ImmutableSortedSet.of());
  }

  @VisibleForTesting
  SourcePath getSource() {
    return src;
  }

  private Path getCopiedPath() {
    Preconditions.checkState(mode == ExportFileDescription.Mode.COPY);
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s")
        .resolve(name);
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolver resolver = context.getSourcePathResolver();

    if (resolver.getFilesystem(src).isDirectory(resolver.getRelativePath(src))) {
      handleDirectory(context.getEventBus());
    }

    // This file is copied rather than symlinked so that when it is included in an archive zip and
    // unpacked on another machine, it is an ordinary file in both scenarios.
    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    if (mode == ExportFileDescription.Mode.COPY) {
      Path out = getCopiedPath();
      builder.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), out.getParent())));
      if (resolver.getFilesystem(src).isDirectory(resolver.getRelativePath(src))) {
        builder.add(
            CopyStep.forDirectory(
                getProjectFilesystem(),
                resolver.getAbsolutePath(src),
                out,
                CopyStep.DirectoryMode.CONTENTS_ONLY));
      } else {
        builder.add(CopyStep.forFile(getProjectFilesystem(), resolver.getAbsolutePath(src), out));
      }
      buildableContext.recordArtifact(out);
    }

    return builder.build();
  }

  private void handleDirectory(BuckEventBus eventBus) {
    switch (directoryAction) {
      case FAIL:
        throw new HumanReadableException(getDirectoryViolationMessage(src));
      case WARN:
        eventBus.post(ConsoleEvent.warning(getDirectoryViolationMessage(src)));
        break;
      case ALLOW:
        break;
      default:
        throw new IllegalStateException();
    }
  }

  private static String getDirectoryViolationMessage(SourcePath src) {
    return String.format("Trying to export a directory '%s' but it is not allowed.", src);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    // In reference mode, we just return the relative path to the source, as we've already verified
    // that our filesystem matches that of the source.  In copy mode, we return the path we've
    // allocated for the copy.
    return mode == ExportFileDescription.Mode.REFERENCE
        ? ForwardingBuildTargetSourcePath.of(getBuildTarget(), src)
        : ExplicitBuildTargetSourcePath.of(getBuildTarget(), getCopiedPath());
  }

  @Override
  public String getOutputName() {
    return name;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    // When using reference mode, we need to make sure that any build rule that builds the source
    // is built when we are, so accomplish this by exporting it as a runtime dep.
    Optional<BuildRule> rule = ruleFinder.getRule(src);
    return mode == ExportFileDescription.Mode.REFERENCE
        ? RichStream.from(rule).map(BuildRule::getBuildTarget)
        : Stream.empty();
  }

  @Override
  public boolean isCacheable() {
    // This rule just copies a file (in COPY mode), so caching is not beneficial here.
    return false;
  }
}
