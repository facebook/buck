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

package com.facebook.buck.file;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.isolatedsteps.common.CopyIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.util.MoreMaps;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Rule which copies files to the output path using the given layout. */
public class CopyTree extends ModernBuildRule<CopyTree.Impl> {

  public CopyTree(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder finder,
      ImmutableSortedMap<ForwardRelPath, SourcePath> paths) {
    super(buildTarget, filesystem, finder, new Impl(paths));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(Impl.OUTPUT_PATH);
  }

  /** Rule implementation. */
  static class Impl implements Buildable {

    private static final OutputPath OUTPUT_PATH = new OutputPath("copy_tree");

    // `ForwardRelativePath` cannot be added to rule keys, so we stringify it below.
    private final ImmutableSortedMap<ForwardRelPath, SourcePath> paths;

    @AddToRuleKey private final Supplier<ImmutableMap<String, SourcePath>> stringifedPaths;

    Impl(ImmutableSortedMap<ForwardRelPath, SourcePath> paths) {
      this.paths = paths;
      this.stringifedPaths =
          Suppliers.memoize(() -> MoreMaps.transformKeys(paths, ForwardRelPath::toString));
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      ImmutableList.Builder<Step> builder = ImmutableList.builder();
      RelPath output = outputPathResolver.resolvePath(OUTPUT_PATH);
      Set<RelPath> dirs = new HashSet<>();
      paths.forEach(
          (name, path) -> {
            RelPath dest = output.resolve(name);
            if (dest.getParent() != null && dirs.add(dest.getParent())) {
              builder.add(MkdirIsolatedStep.of(dest.getParent()));
            }
            builder.add(
                CopyIsolatedStep.forFile(
                    buildContext.getSourcePathResolver().getAbsolutePath(path).getPath(),
                    output.resolve(name.toPath(filesystem.getFileSystem()))));
          });
      return builder.build();
    }
  }
}
