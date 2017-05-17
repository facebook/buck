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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.rules.AbstractBuildRuleWithResolver;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

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
public class ExportFile extends AbstractBuildRuleWithResolver
    implements HasOutputName, HasRuntimeDeps {

  private final SourcePathRuleFinder ruleFinder;
  @AddToRuleKey private final String name;
  @AddToRuleKey private final ExportFileDescription.Mode mode;
  @AddToRuleKey private final SourcePath src;

  public ExportFile(
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver resolver,
      String name,
      ExportFileDescription.Mode mode,
      SourcePath src) {
    super(buildRuleParams, resolver);
    this.ruleFinder = ruleFinder;
    this.name = name;
    this.mode = mode;
    this.src = src;
  }

  @VisibleForTesting
  SourcePath getSource() {
    return src;
  }

  private Path getCopiedPath() {
    Preconditions.checkState(mode == ExportFileDescription.Mode.COPY);
    return getProjectFilesystem()
        .getBuckPaths()
        .getGenDir()
        .resolve(getBuildTarget().getBasePath())
        .resolve(name);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolver resolver = context.getSourcePathResolver();

    // This file is copied rather than symlinked so that when it is included in an archive zip and
    // unpacked on another machine, it is an ordinary file in both scenarios.
    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    if (mode == ExportFileDescription.Mode.COPY) {
      Path out = getCopiedPath();
      builder.add(MkdirStep.of(getProjectFilesystem(), out.getParent()));
      builder.add(RmStep.of(getProjectFilesystem(), out).withRecursive(true));
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

  @Override
  public SourcePath getSourcePathToOutput() {
    // In reference mode, we just return the relative path to the source, as we've already verified
    // that our filesystem matches that of the source.  In copy mode, we return the path we've
    // allocated for the copy.
    return mode == ExportFileDescription.Mode.REFERENCE
        ? src
        : new ExplicitBuildTargetSourcePath(getBuildTarget(), getCopiedPath());
  }

  @Override
  public String getOutputName() {
    return name;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    // When using reference mode, we need to make sure that any build rule that builds the source
    // is built when we are, so accomplish this by exporting it as a runtime dep.
    Optional<BuildRule> rule = ruleFinder.getRule(src);
    return mode == ExportFileDescription.Mode.REFERENCE && rule.isPresent()
        ? Stream.of(rule.get().getBuildTarget())
        : Stream.empty();
  }
}
