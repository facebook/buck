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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class BuildRuleFactoryParams {

  private final ProjectFilesystem filesystem;
  public final BuildTargetParser buildTargetParser;
  public final BuildTargetPatternParser buildTargetPatternParser;
  public final BuildTarget target;
  private final ParseContext buildFileParseContext;
  private final BuildRuleBuilderParams abstractBuildRuleFactoryParams;

  public BuildRuleFactoryParams(
      ProjectFilesystem filesystem,
      BuildTargetParser buildTargetParser,
      BuildTarget target,
      RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    this.filesystem = filesystem;
    this.buildTargetParser = buildTargetParser;
    this.buildTargetPatternParser = new BuildTargetPatternParser();
    this.target = Preconditions.checkNotNull(target);
    this.buildFileParseContext = ParseContext.forBaseName(target.getBaseName());

    this.abstractBuildRuleFactoryParams = new BuildRuleBuilderParams(filesystem,
        ruleKeyBuilderFactory);
  }

  /**
   * For the specified string, return a corresponding file path that is relative to the project
   * root. It is expected that {@code path} is a path relative to the directory containing the build
   * file in which it was declared. This method will also assert that the file exists.
   */
  public Path resolveFilePathRelativeToBuildFileDirectory(String path) {
    return Paths.get(resolvePathAgainstBuildTargetBase(path));
  }

  private String resolvePathAgainstBuildTargetBase(String path) {
    if (target.isInProjectRoot()) {
      return path;
    } else {
      return String.format("%s/%s", target.getBasePath(), path);
    }
  }

  public BuildTarget resolveBuildTarget(String target) {
    return buildTargetParser.parse(target, buildFileParseContext);
  }

  public ProjectFilesystem getProjectFilesystem() {
    return filesystem;
  }

  public RuleKeyBuilderFactory getRuleKeyBuilderFactory() {
    return abstractBuildRuleFactoryParams.getRuleKeyBuilderFactory();
  }

}
