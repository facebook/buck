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
package com.facebook.buck.parser;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.ImmediateDirectoryBuildTargetPattern;
import com.facebook.buck.model.SingletonBuildTargetPattern;
import com.facebook.buck.model.SubdirectoryBuildTargetPattern;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

public class BuildTargetPatternParser {

  @VisibleForTesting
  public static final String VISIBILITY_PUBLIC = "PUBLIC";
  private static final String BUILD_RULE_PREFIX = "//";
  private static final String WILDCARD_BUILD_RULE_SUFFIX = "...";
  private static final String BUILD_RULE_SEPARATOR = ":";

  private BuildTargetParser buildTargetParser;

  public BuildTargetPatternParser(ProjectFilesystem projectFilesystem) {
    buildTargetParser = new BuildTargetParser(projectFilesystem);
  }

  /**
   * 1. //src/com/facebook/buck/cli:cli will be convert to a single build target
   * 2. //src/com/facebook/buck/cli: will match all in the same directory.
   * 3. //src/com/facebook/buck/cli/... will match all in or under that directory.
   * For case 2 and 3, parseContext is expected to be {@link ParseContext#forVisibilityArgument()}.
   */
  public BuildTargetPattern parse(
      String buildTargetPattern,
      ParseContext parseContext) throws NoSuchBuildTargetException {

    Preconditions.checkNotNull(buildTargetPattern);

    if (buildTargetPattern.equals(VISIBILITY_PUBLIC)) {
      if (parseContext.getType() != ParseContext.Type.VISIBILITY) {
        throw new BuildTargetParseException(
            String.format("%s not supported in the parse context", VISIBILITY_PUBLIC));
      } else {
        return BuildTargetPattern.MATCH_ALL;
      }
    }

    Preconditions.checkArgument(buildTargetPattern.startsWith(BUILD_RULE_PREFIX),
        "buildTargetPattern must start with //");
    Preconditions.checkNotNull(parseContext);

    if (buildTargetPattern.endsWith(WILDCARD_BUILD_RULE_SUFFIX)) {
      if (parseContext.getType() != ParseContext.Type.VISIBILITY) {
        throw new BuildTargetParseException(
            String.format("%s cannot end with ...", buildTargetPattern));
      } else {
        if (buildTargetPattern.contains(BUILD_RULE_SEPARATOR)) {
          throw new BuildTargetParseException(String.format(
              "%s cannot contain colon", buildTargetPattern));
        }
        String basePathWithSlash = buildTargetPattern.substring(
            BUILD_RULE_PREFIX.length(),
            buildTargetPattern.length() - WILDCARD_BUILD_RULE_SUFFIX.length());
        return new SubdirectoryBuildTargetPattern(basePathWithSlash);
      }
    } else {
      BuildTarget target = buildTargetParser.parse(buildTargetPattern, parseContext);
      if (target.getShortName().isEmpty()) {
        return new ImmediateDirectoryBuildTargetPattern(target.getBasePathWithSlash());
      } else {
        return new SingletonBuildTargetPattern(target.getFullyQualifiedName());
      }
    }
  }
}
