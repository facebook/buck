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
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

public class TargetsCommandOptions extends AbstractCommandOptions {

  @Option(name = "--referenced_file",
      usage = "The referenced file list, --referenced_file file1 file2  ... fileN --other_option",
      handler = StringSetOptionHandler.class)
  private Supplier<ImmutableSet<String>> referencedFiles;

  @Option(name = "--type",
      usage = "The types of target to filter by, --type type1 type2 ... typeN --other_option",
      handler = StringSetOptionHandler.class)
  private Supplier<ImmutableSet<String>> types;

  @Option(name = "--json", usage = "Print JSON representation of each target")
  private boolean json;

  @Option(name = "--resolvealias",
      usage = "Print the fully-qualified build target for the specified alias[es]")
  private boolean isResolveAlias;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public TargetsCommandOptions(BuckConfig buckConfig) {
    super(buckConfig);
  }

  public ImmutableSet<String> getTypes() {
    return types.get();
  }

  /**
   * Filter files under the project root, and convert to canonical relative path style.
   * For example, the project root is /project,
   * 1. file path /project/./src/com/facebook/./test/../Test.java will be converted to
   *    src/com/facebook/Test.java
   * 2. file path /otherproject/src/com/facebook/Test.java will be ignored.
   */
  public static ImmutableSet<String> getCanonicalFilesUnderProjectRoot(
      File projectRoot, ImmutableSet<String> nonCanonicalFilePaths)
      throws IOException {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    String projectRootCanonicalFullPathWithEndSlash = projectRoot.getCanonicalPath() + "/";
    for (String filePath : nonCanonicalFilePaths) {
      String canonicalFullPath = new File(filePath).getCanonicalPath();

      // Ignore files that aren't under project root.
      if (canonicalFullPath.startsWith(projectRootCanonicalFullPathWithEndSlash)) {
        builder.add(
            canonicalFullPath.substring(projectRootCanonicalFullPathWithEndSlash.length()));
      }
    }
    return builder.build();
  }

  public ImmutableSet<String> getReferencedFiles(File projectRoot)
      throws IOException {
    return getCanonicalFilesUnderProjectRoot(projectRoot, referencedFiles.get());
  }

  public boolean getPrintJson() {
    return json;
  }

  /** @return the arguments passed to this command. */
  public Iterable<String> getArguments() {
    return Collections.unmodifiableList(arguments);
  }

  /** @return {@code true} if {@code --resolvealias} was specified. */
  public boolean isResolveAlias() {
    return isResolveAlias;
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
