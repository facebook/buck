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

import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A set of parameters passed to a {@link BuildRuleFactory}.
 */
public final class BuildRuleFactoryParams {
  public static final String GENFILE_PREFIX = "BUCKGEN:";

  private final Map<String, ?> instance;
  private final ProjectFilesystem filesystem;
  private final BuildFileTree buildFiles;
  public final BuildTargetParser buildTargetParser;
  public final BuildTargetPatternParser buildTargetPatternParser;
  public final BuildTarget target;
  private final ParseContext buildFileParseContext;
  private final boolean ignoreFileExistenceChecks;
  private final BuildRuleBuilderParams abstractBuildRuleFactoryParams;

  public BuildRuleFactoryParams(
      Map<String, ?> instance,
      ProjectFilesystem filesystem,
      BuildFileTree buildFiles,
      BuildTargetParser buildTargetParser,
      BuildTarget target,
      RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    this(instance,
        filesystem,
        buildFiles,
        buildTargetParser,
        target,
        ruleKeyBuilderFactory,
        false /* ignoreFileExistenceChecks */);
  }

  @VisibleForTesting
  BuildRuleFactoryParams(
      Map<String, ?> instance,
      ProjectFilesystem filesystem,
      BuildFileTree buildFiles,
      BuildTargetParser buildTargetParser,
      BuildTarget target,
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      boolean ignoreFileExistenceChecks) {
    this.instance = instance;
    this.filesystem = filesystem;
    this.buildFiles = Preconditions.checkNotNull(buildFiles);
    this.buildTargetParser = buildTargetParser;
    this.buildTargetPatternParser = new BuildTargetPatternParser(filesystem);
    this.target = Preconditions.checkNotNull(target);
    this.buildFileParseContext = ParseContext.forBaseName(target.getBaseName());
    this.ignoreFileExistenceChecks = ignoreFileExistenceChecks;

    this.abstractBuildRuleFactoryParams = new BuildRuleBuilderParams(filesystem,
        ruleKeyBuilderFactory);
  }

  /** This is package-private so that only AbstractBuildRuleFactory can access it. */
  BuildRuleBuilderParams getAbstractBuildRuleFactoryParams() {
    return abstractBuildRuleFactoryParams;
  }

  /**
   * For the specified string, return a corresponding file path that is relative to the project
   * root. It is expected that {@code path} is a path relative to the directory containing the build
   * file in which it was declared. This method will also assert that the file exists.
   * <p>
   * However, if {@code path} corresponds to a generated file, then {@code path} is treated as a
   * path relative to the parallel build file directory in the generated files directory. In that
   * case, its existence will not be verified.
   */
  public Path resolveFilePathRelativeToBuildFileDirectory(String path) {
    if (path.startsWith(GENFILE_PREFIX)) {
      path = path.substring(GENFILE_PREFIX.length());
      return Paths.get(BuckConstant.GEN_DIR, resolvePathAgainstBuildTargetBase(path));
    } else {
      Path fullPath = Paths.get(resolvePathAgainstBuildTargetBase(path));
      File file = filesystem.getFileForRelativePath(fullPath);

      // TODO(mbolin): Eliminate this temporary exemption for symbolic links.
      if (Files.isSymbolicLink(file.toPath())) {
        return fullPath;
      }

      if (!ignoreFileExistenceChecks && !file.isFile()) {
        throw new RuntimeException(String.format(
            "Not an ordinary file: %s. Failure when trying to create %s.",
            fullPath,
            target));
      }

      // First, verify that the path is a descendant of the directory containing the build file.
      String basePath = target.getBasePath();
      if (!basePath.isEmpty() && !fullPath.startsWith(basePath)) {
        throw new RuntimeException(file + " is not a descendant of " + target.getBasePath());
      }

      checkFullPath(fullPath.toString());

      return fullPath;
    }
  }

  private void checkFullPath(String fullPath) {
    if (fullPath.contains("..")) {
      throw new HumanReadableException(
          "\"%s\" in target \"%s\" refers to a parent directory.",
          fullPath,
          target.getFullyQualifiedName());
    }

    String ancestor = buildFiles.getBasePathOfAncestorTarget(fullPath);
    if (!target.getBasePath().equals(ancestor)) {
      throw new HumanReadableException(
          "\"%s\" in target \"%s\" crosses a buck package boundary. Find the nearest BUCK file " +
          "in the directory containing this file and refer to the rule referencing the " +
          "desired file.",
          fullPath,
          target);
    }
  }

  private String resolvePathAgainstBuildTargetBase(String path) {
    if (target.getBasePath().isEmpty()) {
      return path;
    } else {
      return String.format("%s/%s", target.getBasePath(), path);
    }
  }

  public BuildTarget resolveBuildTarget(String target) throws NoSuchBuildTargetException {
    return buildTargetParser.parse(target, buildFileParseContext);
  }

  public Optional<Integer> getOptionalIntegerAttribute(String attributeName) {
    Object value = instance.get(attributeName);
    if (value != null) {
      if (value instanceof Number) {
        Number number = (Number) value;
        if (number.intValue() == number.floatValue()) {
          return Optional.of(number.intValue());
        }
      }
      throw new RuntimeException(String.format("Expected a integer for %s in %s but was %s",
          attributeName,
          target.getBuildFilePath(),
          value));
    } else {
      return Optional.absent();
    }
  }

  /** @return non-null list that may be empty */
  @SuppressWarnings("unchecked")
  public List<String> getOptionalListAttribute(String attributeName) {
    Object value = instance.get(attributeName);
    if (value != null) {
      if (value instanceof List) {
        return (List<String>) value;
      } else {
        throw new RuntimeException(String.format("Expected an array for %s in %s but was %s",
            attributeName,
            target.getBuildFilePath(),
            value));
      }
    } else {
      return ImmutableList.of();
    }
  }

  @Nullable
  public Object getNullableRawAttribute(String attributeName) {
    return instance.get(attributeName);
  }

  public ProjectFilesystem getProjectFilesystem() {
    return filesystem;
  }

  public RuleKeyBuilderFactory getRuleKeyBuilderFactory() {
    return abstractBuildRuleFactoryParams.getRuleKeyBuilderFactory();
  }
}
