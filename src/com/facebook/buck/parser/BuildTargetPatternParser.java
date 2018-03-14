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
import com.facebook.buck.rules.CellPathResolver;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Context for parsing build target names. Fully-qualified target names are parsed the same
 * regardless of the context.
 */
public abstract class BuildTargetPatternParser<T> {

  private static final String BUILD_RULE_PREFIX = "//";
  private static final String WILDCARD_BUILD_RULE_SUFFIX = "...";
  private static final String BUILD_RULE_SEPARATOR = ":";

  private final String baseName;

  protected BuildTargetPatternParser(String baseName) {
    this.baseName = Preconditions.checkNotNull(baseName);
  }

  public String getBaseName() {
    return baseName;
  }

  protected boolean isWildCardAllowed() {
    return false;
  }

  /**
   * 1. //src/com/facebook/buck/cli:cli will be converted to a single build target 2.
   * //src/com/facebook/buck/cli: will match all in the same directory. 3.
   * //src/com/facebook/buck/cli/... will match all in or under that directory. For case 2 and 3,
   * parseContext is expected to be {@link BuildTargetPatternParser#forVisibilityArgument()}.
   */
  public final T parse(CellPathResolver cellNames, String buildTargetPattern) {
    Preconditions.checkArgument(
        buildTargetPattern.contains(BUILD_RULE_PREFIX),
        String.format("'%s' must start with '//' or a cell followed by '//'", buildTargetPattern));

    if (buildTargetPattern.endsWith("/" + WILDCARD_BUILD_RULE_SUFFIX)) {
      return createWildCardPattern(cellNames, buildTargetPattern);
    }

    BuildTarget target = BuildTargetParser.INSTANCE.parse(buildTargetPattern, this, cellNames);
    if (target.getShortNameAndFlavorPostfix().isEmpty()) {
      return createForChildren(target.getCellPath(), target.getBasePath());
    } else {
      return createForSingleton(target);
    }
  }

  private T createWildCardPattern(CellPathResolver cellNames, String buildTargetPatternWithCell) {
    if (!isWildCardAllowed()) {
      throw new BuildTargetParseException(
          String.format("'%s' cannot end with '...'", buildTargetPatternWithCell));
    }

    Path cellPath;
    String buildTargetPattern;
    int index = buildTargetPatternWithCell.indexOf(BUILD_RULE_PREFIX);
    if (index > 0) {
      cellPath =
          cellNames.getCellPathOrThrow(Optional.of(buildTargetPatternWithCell.substring(0, index)));
      buildTargetPattern = buildTargetPatternWithCell.substring(index);
    } else {
      cellPath = cellNames.getCellPathOrThrow(Optional.empty());
      buildTargetPattern = buildTargetPatternWithCell;
    }

    if (buildTargetPattern.contains(BUILD_RULE_SEPARATOR)) {
      throw new BuildTargetParseException(
          String.format("'%s' cannot contain colon", buildTargetPattern));
    }

    if (!buildTargetPattern.equals(BUILD_RULE_PREFIX + WILDCARD_BUILD_RULE_SUFFIX)) {
      String basePathWithPrefix =
          buildTargetPattern.substring(
              0, buildTargetPattern.length() - WILDCARD_BUILD_RULE_SUFFIX.length() - 1);
      BuildTargetParser.checkBaseName(basePathWithPrefix, buildTargetPattern);
    }

    String basePathWithSlash =
        buildTargetPattern.substring(
            BUILD_RULE_PREFIX.length(),
            buildTargetPattern.length() - WILDCARD_BUILD_RULE_SUFFIX.length());
    // Make sure the basePath comes from the same underlying filesystem.
    Path basePath = cellPath.getFileSystem().getPath(basePathWithSlash);
    return createForDescendants(cellPath, basePath);
  }

  /**
   * Used when parsing target names relative to another target, such as in a build file.
   *
   * @param baseName name such as {@code //first-party/orca}
   */
  public static BuildTargetPatternParser<BuildTargetPattern> forBaseName(String baseName) {
    Preconditions.checkNotNull(Strings.emptyToNull(baseName));
    return new BuildFileContext(baseName);
  }

  /** Used when parsing target names in the {@code visibility} argument to a build rule. */
  public static BuildTargetPatternParser<BuildTargetPattern> forVisibilityArgument() {
    return new VisibilityContext();
  }

  /** Used when parsing fully-qualified target names only, such as from the command line. */
  public static BuildTargetPatternParser<BuildTargetPattern> fullyQualified() {
    return new FullyQualifiedContext();
  }

  /**
   * @return description of the target name and context being parsed when an error was encountered.
   *     Examples are ":azzetz in build file //first-party/orca/orcaapp/BUCK" and
   *     "//first-party/orca/orcaapp:mezzenger in context FULLY_QUALIFIED"
   */
  protected abstract T createForDescendants(Path cellPath, Path basePath);

  protected abstract T createForChildren(Path cellPath, Path basePath);

  protected abstract T createForSingleton(BuildTarget target);

  private abstract static class BuildTargetPatternBaseParser
      extends BuildTargetPatternParser<BuildTargetPattern> {
    public BuildTargetPatternBaseParser(String baseName) {
      super(baseName);
    }

    @Override
    public BuildTargetPattern createForDescendants(Path cellPath, Path basePath) {
      return SubdirectoryBuildTargetPattern.of(cellPath, basePath);
    }

    @Override
    public BuildTargetPattern createForChildren(Path cellPath, Path basePath) {
      return ImmediateDirectoryBuildTargetPattern.of(cellPath, basePath);
    }

    @Override
    public BuildTargetPattern createForSingleton(BuildTarget target) {
      return SingletonBuildTargetPattern.of(
          target.getUnflavoredBuildTarget().getCellPath(), target.getFullyQualifiedName());
    }
  }

  private static class BuildFileContext extends BuildTargetPatternBaseParser {

    public BuildFileContext(String basePath) {
      super(basePath);
    }
  }

  /**
   * When parsing a build target for the visibility argument in a build file, targets must be
   * fully-qualified, but wildcards are allowed.
   */
  private static class FullyQualifiedContext extends BuildTargetPatternBaseParser {

    public FullyQualifiedContext() {
      super("");
    }
  }

  private static class VisibilityContext extends BuildTargetPatternBaseParser {

    public VisibilityContext() {
      super("");
    }

    @Override
    protected boolean isWildCardAllowed() {
      return true;
    }
  }
}
