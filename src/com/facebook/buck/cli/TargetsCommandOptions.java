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
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;

import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

public class TargetsCommandOptions extends BuildCommandOptions {

  // TODO(mbolin): Use org.kohsuke.args4j.spi.PathOptionHandler. Currently, we resolve paths
  // manually, which is likely the path to madness.
  @Option(name = "--referenced-file",
      aliases = {"--referenced_file"},
      usage = "The referenced file list, --referenced_file file1 file2  ... fileN --other_option",
      handler = StringSetOptionHandler.class)
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> referencedFiles;

  @Option(name = "--detect-test-changes",
      usage = "Modifies the --referenced-file flag resolution to pretend that targets depend on " +
          "their tests (experimental)")
  private boolean isDetectTestChanges;

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

  @Option(name = "--show-output",
      aliases = {"--show_output"},
      usage = "Print the absolute path to the output for each rule after the rule name.")
  private boolean isShowOutput;

  @Option(name = "--show-rulekey",
      aliases = {"--show_rulekey"},
      usage = "Print the RuleKey of each rule after the rule name.")
  private boolean isShowRuleKey;

  @Option(name = "--show-target-hash",
      usage = "Print a stable hash of each target after the target name.")
  private boolean isShowTargetHash;

  public TargetsCommandOptions(BuckConfig buckConfig) {
    super(buckConfig);
  }

  public ImmutableSet<String> getTypes() {
    return types.get();
  }

  public PathArguments.ReferencedFiles getReferencedFiles(Path projectRoot)
      throws IOException {
    return PathArguments.getCanonicalFilesUnderProjectRoot(projectRoot, referencedFiles.get());
  }

  /** @return {@code true} if {@code --detect-test-changes} was specified. */
  public boolean isDetectTestChanges() {
    return isDetectTestChanges;
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

  /** @return {@code true} if {@code --show-targethash} was specified. */
  public boolean isShowTargetHash() {
    return isShowTargetHash;
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
