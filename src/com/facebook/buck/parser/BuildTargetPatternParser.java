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

import static com.facebook.buck.util.BuckConstant.BUILD_RULES_FILE_NAME;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.ImmediateDirectoryBuildTargetPattern;
import com.facebook.buck.model.SingletonBuildTargetPattern;
import com.facebook.buck.model.SubdirectoryBuildTargetPattern;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Context for parsing build target names. Fully-qualified target names are parsed the same
 * regardless of the context.
 */
@Immutable
public abstract class BuildTargetPatternParser {

  private static final String VISIBILITY_PUBLIC = "PUBLIC";
  private static final String BUILD_RULE_PREFIX = "//";
  private static final String WILDCARD_BUILD_RULE_SUFFIX = "...";
  private static final String BUILD_RULE_SEPARATOR = ":";

  @Nullable
  private final String baseName;

  private final BuildTargetParser buildTargetParser;

  private BuildTargetPatternParser(BuildTargetParser targetParser, String baseName) {
    this.baseName = baseName;
    this.buildTargetParser = targetParser;
  }

  @Nullable
  public String getBaseName() {
    return baseName;
  }

  @Nullable
  public String getBaseNameWithSlash() {
    return BuildTarget.getBaseNameWithSlash(baseName);
  }

  protected boolean isPublicVisibilityAllowed() {
    return false;
  }

  protected boolean isWildCardAllowed() {
    return false;
  }

  /**
   * 1. //src/com/facebook/buck/cli:cli will be converted to a single build target
   * 2. //src/com/facebook/buck/cli: will match all in the same directory.
   * 3. //src/com/facebook/buck/cli/... will match all in or under that directory.
   * For case 2 and 3, parseContext is expected to be
   * {@link BuildTargetPatternParser#forVisibilityArgument(BuildTargetParser)}.
   */
  public final BuildTargetPattern parse(String buildTargetPattern)
      throws NoSuchBuildTargetException {
    if (VISIBILITY_PUBLIC.equals(buildTargetPattern)) {
      if (isPublicVisibilityAllowed()) {
        return BuildTargetPattern.MATCH_ALL;
      } else {
        throw new BuildTargetParseException(
            String.format("%s not supported in the parse context", VISIBILITY_PUBLIC));
      }
    }

    Preconditions.checkArgument(
        buildTargetPattern.startsWith(BUILD_RULE_PREFIX),
        String.format("'%s' must start with '//'", buildTargetPattern));

    if (buildTargetPattern.endsWith(WILDCARD_BUILD_RULE_SUFFIX)) {
      if (isWildCardAllowed()) {
        if (buildTargetPattern.contains(BUILD_RULE_SEPARATOR)) {
          throw new BuildTargetParseException(
              String.format(
                  "'%s' cannot contain colon", buildTargetPattern));
        }
        String basePathWithSlash = buildTargetPattern.substring(
            BUILD_RULE_PREFIX.length(),
            buildTargetPattern.length() - WILDCARD_BUILD_RULE_SUFFIX.length());
        return new SubdirectoryBuildTargetPattern(basePathWithSlash);
      } else {
        throw new BuildTargetParseException(
            String.format("'%s' cannot end with '...'", buildTargetPattern));
      }
    }

    BuildTarget target = buildTargetParser.parse(buildTargetPattern, this);
    if (target.getShortNameAndFlavorPostfix().isEmpty()) {
      return new ImmediateDirectoryBuildTargetPattern(target.getBasePathWithSlash());
    } else {
      return new SingletonBuildTargetPattern(target.getFullyQualifiedName());
    }
  }

  /**
   * Used when parsing target names relative to another target, such as in a build file.
   * @param baseName name such as {@code //first-party/orca}
   */
  public static BuildTargetPatternParser forBaseName(
      BuildTargetParser targetParser,
      String baseName) {
    Preconditions.checkNotNull(Strings.emptyToNull(baseName));
    return new BuildFileContext(targetParser, baseName);
  }

  /**
   * Used when parsing target names in the {@code visibility} argument to a build rule.
   */
  public static BuildTargetPatternParser forVisibilityArgument(BuildTargetParser targetParser) {
    return new VisibilityContext(targetParser);
  }

  /**
   * Used when parsing fully-qualified target names only, such as from the command line.
   */
  public static BuildTargetPatternParser fullyQualified(BuildTargetParser targetParser) {
    return new FullyQualifiedContext(targetParser);
  }

  /**
   * @return description of the target name and context being parsed when an error was encountered.
   *     Examples are ":azzetz in build file //first-party/orca/orcaapp/BUCK" and
   *     "//first-party/orca/orcaapp:mezzenger in context FULLY_QUALIFIED"
   */
  public abstract String makeTargetDescription(String buildTargetName);

  private static class BuildFileContext extends BuildTargetPatternParser {

    public BuildFileContext(BuildTargetParser targetParser, String basePath) {
      super(targetParser, basePath);
    }

    @Override
    public String makeTargetDescription(String buildTargetName) {
      return String.format(
          "%s in build file %s%s",
          buildTargetName,
          getBaseNameWithSlash(),
          BUILD_RULES_FILE_NAME);
    }
  }

  /**
   * When parsing a build target for the visibility argument in a build file, targets must be
   * fully-qualified, but wildcards are allowed.
   */
  private static class FullyQualifiedContext extends BuildTargetPatternParser {
    public FullyQualifiedContext(BuildTargetParser targetParser) {
      super(targetParser, "");
    }

    @Override
    public String makeTargetDescription(String buildTargetName) {
      return String.format("%s in fully qualified context.", buildTargetName);
    }
  }

  private static class VisibilityContext extends BuildTargetPatternParser {
    public VisibilityContext(BuildTargetParser targetParser) {
      super(targetParser, "");
    }

    @Override
    public String makeTargetDescription(String buildTargetName) {
      return String.format("%s in context visibility", buildTargetName);
    }


    @Override
    protected boolean isPublicVisibilityAllowed() {
      return true;
    }

    @Override
    protected boolean isWildCardAllowed() {
      return true;
    }
  }
}
