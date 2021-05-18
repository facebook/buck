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

package com.facebook.buck.apple;

import com.facebook.buck.apple.clang.VFSOverlay;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.RemoteExecutionEnabled;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * For Swift projects, we have to build two modulemap files, one for the underlying (private) module
 * and the other for dependent modules. This creates a modulemap redefinition error because both
 * search paths are accessible to the compiler. To get around this, we're creating a fake search
 * path and then using a contextual VFS overlay to map them to the correct modulemap for
 * underlying/dependent modules.
 */
public class AppleVFSOverlayBuildRule extends ModernBuildRule<AppleVFSOverlayBuildRule.Impl> {

  // the filename of the overlay is important, if we use the same
  // name as Xcode then the flag is skipped when serializing debug info
  // https://github.com/apple/swift/blob/af8cf15e22661a91086930d03a033e3917feb4f2/lib/Serialization/Serialization.cpp#L992-L1006
  public static String VFS_OVERLAY_FILENAME = "unextended-module-overlay.yaml";

  AppleVFSOverlayBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder finder,
      SourcePath underlyingModulemapPath,
      RelPath exportedHeadersWithModulemapPath) {
    super(
        buildTarget,
        filesystem,
        finder,
        new Impl(underlyingModulemapPath, exportedHeadersWithModulemapPath));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().yamlPath);
  }

  @Override
  public boolean isCacheable() {
    // We don't want to cache the output of this rule because it contains absolute paths.
    return false;
  }

  /** Implementation of the AppleVFSOverlayBuildRule */
  static class Impl implements Buildable {

    private static final Logger LOG = Logger.get(AppleVFSOverlayBuildRule.Impl.class);

    @AddToRuleKey private final SourcePath underlyingModulemapPath;
    @AddToRuleKey private final String exportedHeadersWithModulemapPath;
    @AddToRuleKey private final OutputPath yamlPath;

    // TODO: (andyyhope): swap to compiler-invocation relative paths
    @CustomFieldBehavior(RemoteExecutionEnabled.class)
    private final boolean remoteExecutionEnabled = false;

    Impl(SourcePath underlyingModulemapPath, RelPath exportedHeadersWithModulemapPath) {
      this.underlyingModulemapPath = underlyingModulemapPath;
      this.exportedHeadersWithModulemapPath = exportedHeadersWithModulemapPath.toString();
      this.yamlPath = new OutputPath(VFS_OVERLAY_FILENAME);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      RelPath yamlRelPath = outputPathResolver.resolvePath(yamlPath);

      return ImmutableList.of(
          new AbstractExecutionStep("apple-swift-vfs-overlay-step") {
            @Override
            public StepExecutionResult execute(StepExecutionContext context) throws IOException {
              ImmutableSortedMap.Builder<Path, Path> vfsBuilder = ImmutableSortedMap.naturalOrder();

              AbsPath underlyingModulemapAbsPath =
                  buildContext.getSourcePathResolver().getAbsolutePath(underlyingModulemapPath);
              AbsPath exportedModulemapAbsPath =
                  filesystem.resolve(exportedHeadersWithModulemapPath);

              SimpleFileVisitor<Path> fileVisitor =
                  new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                      Path relPath = underlyingModulemapAbsPath.getPath().relativize(file);
                      Path exportedPath = exportedModulemapAbsPath.getPath().resolve(relPath);
                      vfsBuilder.put(exportedPath, file);
                      return FileVisitResult.CONTINUE;
                    }
                  };
              Files.walkFileTree(underlyingModulemapAbsPath.getPath(), fileVisitor);
              VFSOverlay vfsOverlay = new VFSOverlay(vfsBuilder.build());
              try {
                String render = vfsOverlay.render();
                filesystem.createParentDirs(yamlRelPath.getPath());
                filesystem.writeContentsToPath(render, yamlRelPath.getPath());
                return StepExecutionResults.SUCCESS;
              } catch (IOException e) {
                LOG.debug("Couldn't generate VFS overlay: %s", e.getMessage());
                return StepExecutionResults.ERROR;
              }
            }
          });
    }
  }
}
