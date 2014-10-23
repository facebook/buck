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

package com.facebook.buck.cli;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;

import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

public class TargetsCommandOptions extends BuildCommandOptions {

  // TODO(mbolin): Use org.kohsuke.args4j.spi.PathOptionHandler. Currently, we resolve paths
  // manually, which is likely the path to madness.
  @Option(name = "--referenced_file",
      usage = "The referenced file list, --referenced_file file1 file2  ... fileN --other_option",
      handler = StringSetOptionHandler.class)
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> referencedFiles;

  @Option(name = "--type",
      usage = "The types of target to filter by, --type type1 type2 ... typeN --other_option",
      handler = StringSetOptionHandler.class)
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> types;

  @Option(name = "--json", usage = "Print JSON representation of each target")
  private boolean json;

  @Option(name = "--resolvealias",
      usage = "Print the fully-qualified build target for the specified alias[es]")
  private boolean isResolveAlias;

  @Option(name = "--show_output",
      usage = "Print the absolute path to the output for each rule after the rule name.")
  private boolean isShowOutput;

  @Option(name = "--show_rulekey",
      usage = "Print the RuleKey of each rule after the rule name.")
  private boolean isShowRuleKey;

  public TargetsCommandOptions(BuckConfig buckConfig) {
    super(buckConfig);
  }

  public ImmutableSet<String> getTypes() {
    return types.get();
  }

  static class ReferencedFiles {
    final ImmutableSet<Path> relativePathsUnderProjectRoot;
    final ImmutableSet<Path> absolutePathsOutsideProjectRootOrNonExistingPaths;

    public ReferencedFiles(
        ImmutableSet<Path> relativePathsUnderProjectRoot,
        ImmutableSet<Path> absolutePathsOutsideProjectRootOrNonExistingPaths) {
      this.relativePathsUnderProjectRoot = relativePathsUnderProjectRoot;
      this.absolutePathsOutsideProjectRootOrNonExistingPaths =
          absolutePathsOutsideProjectRootOrNonExistingPaths;
    }
  }

  /**
   * Filter files under the project root, and convert to canonical relative path style.
   * For example, the project root is /project,
   * 1. file path /project/./src/com/facebook/./test/../Test.java will be converted to
   *    src/com/facebook/Test.java
   * 2. file path /otherproject/src/com/facebook/Test.java will be ignored.
   */
  @VisibleForTesting
  static ReferencedFiles getCanonicalFilesUnderProjectRoot(
      Path projectRoot, ImmutableSet<String> nonCanonicalFilePaths)
      throws IOException {
    // toRealPath() is used throughout to resolve symlinks or else the Path.startsWith() check will
    // not be reliable.
    ImmutableSet.Builder<Path> projectFiles = ImmutableSet.builder();
    ImmutableSet.Builder<Path> nonProjectFiles = ImmutableSet.builder();
    Path normalizedRoot = projectRoot.toRealPath();
    for (String filePath : nonCanonicalFilePaths) {
      Path canonicalFullPath = Paths.get(filePath);
      if (!canonicalFullPath.isAbsolute()) {
        canonicalFullPath = projectRoot.resolve(canonicalFullPath);
      }
      if (!canonicalFullPath.toFile().exists()) {
        nonProjectFiles.add(canonicalFullPath);
        continue;
      }
      canonicalFullPath = canonicalFullPath.toRealPath();

      // Ignore files that aren't under project root.
      if (canonicalFullPath.startsWith(normalizedRoot)) {
        Path relativePath = canonicalFullPath.subpath(
            normalizedRoot.getNameCount(),
            canonicalFullPath.getNameCount());
        projectFiles.add(relativePath);
      } else {
        nonProjectFiles.add(canonicalFullPath);
      }
    }
    return new ReferencedFiles(projectFiles.build(), nonProjectFiles.build());
  }

  public ReferencedFiles getReferencedFiles(Path projectRoot)
      throws IOException {
    return getCanonicalFilesUnderProjectRoot(projectRoot, referencedFiles.get());
  }

  public boolean getPrintJson() {
    return json;
  }

  /** @return {@code true} if {@code --resolvealias} was specified. */
  public boolean isResolveAlias() {
    return isResolveAlias;
  }

  /** @return {@code true} if {@code --show_output} was specified. */
  public boolean isShowOutput() {
    return isShowOutput;
  }

  /** @return {@code true} if {@code --show_rulekey} was specified. */
  public boolean isShowRuleKey() {
    return isShowRuleKey;
  }

  /** @return the name of the build target identified by the specified alias or {@code null}. */
  @Nullable
  public String getBuildTargetForAlias(String alias) {
    return getBuckConfig().getBuildTargetForAlias(alias);
  }

  /** @return the build target identified by the specified full path or {@code null}. */
  public BuildTarget getBuildTargetForFullyQualifiedTarget(String target)
      throws NoSuchBuildTargetException {
    return getBuckConfig().getBuildTargetForFullyQualifiedTarget(target);
  }

}
