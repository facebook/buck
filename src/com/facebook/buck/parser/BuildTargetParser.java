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
import com.facebook.buck.model.FlavorParser;
import com.facebook.buck.model.ImmutableBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.HashSet;
import java.util.List;

public class BuildTargetParser {

  private static final String BUILD_RULE_PREFIX = "//";
  private static final String BUILD_RULE_SEPARATOR = ":";
  private static final String REPOSITORY_STARTER = "@";
  private static final Splitter BUILD_RULE_SEPARATOR_SPLITTER = Splitter.on(BUILD_RULE_SEPARATOR);
  private static final List<String> INVALID_BUILD_RULE_SUBSTRINGS = ImmutableList.of("..", "./");

  private final ImmutableMap<Optional<String>, Optional<String>> localToCanonicalRepoNamesMap;
  private final FlavorParser flavorParser = new FlavorParser();

  public BuildTargetParser() {
    // By default, use a canonical names map that only allows targets with no repo name.
    this(ImmutableMap.of(Optional.<String>absent(), Optional.<String>absent()));
  }

  /**
   *
   * @param localToCanonicalRepoNamesMap
   *          Internally, buck uses a unique, global name to refer to each repository.  All targets
   *          that live in repo X are represented internally using this unique name. However, other
   *          repos might have their own names for X, and rules inside of X will refer to each other
   *          with no explicit repo at all. This map provides the translation from local repo names
   *          used within the current repo to their unique, global repo names.
   */
  public BuildTargetParser(
      ImmutableMap<Optional<String>, Optional<String>> localToCanonicalRepoNamesMap) {
    this.localToCanonicalRepoNamesMap = localToCanonicalRepoNamesMap;
  }

  /**
   * @param buildTargetName either a fully-qualified name or relative to the {@link BuildTargetPatternParser}.
   *     For example, inside {@code first-party/orca/orcaapp/BUILD}, which can be obtained by
   *     calling {@code ParseContext.forBaseName("first-party/orca/orcaapp")},
   *     {@code //first-party/orca/orcaapp:assets} and {@code :assets} refer to the same target.
   *     However, from the command line the context is obtained by calling
   *     {@link BuildTargetPatternParser#fullyQualified(BuildTargetParser)} and relative names are
   *     not recognized.
   * @param buildTargetPatternParser how targets should be interpreted, such in the context of a
   *     specific build file or only as fully-qualified names (as is the case for targets from the
   *     command line).
   */
  public BuildTarget parse(
      String buildTargetName,
      BuildTargetPatternParser buildTargetPatternParser) {

    for (String invalidSubstring : INVALID_BUILD_RULE_SUBSTRINGS) {
      if (buildTargetName.contains(invalidSubstring)) {
        throw new BuildTargetParseException(
            String.format("%s cannot contain %s", buildTargetName, invalidSubstring));
      }
    }

    if (buildTargetName.endsWith(BUILD_RULE_SEPARATOR) &&
        !buildTargetPatternParser.isWildCardAllowed()) {
      throw new BuildTargetParseException(
          String.format("%s cannot end with a colon", buildTargetName));
    }

    Optional<String> givenRepoName = Optional.absent();
    String targetAfterRepo = buildTargetName;
    if (buildTargetName.startsWith(REPOSITORY_STARTER)) {
      if (!buildTargetName.contains(BUILD_RULE_PREFIX)) {
        throw new BuildTargetParseException(
            String.format(
                "Cross-repo paths must contain %s (found %s)",
                BUILD_RULE_PREFIX,
                buildTargetName));
      }
      int slashIndex = buildTargetName.indexOf(BUILD_RULE_PREFIX);
      givenRepoName = Optional.of(
          buildTargetName.substring(REPOSITORY_STARTER.length(), slashIndex));
      targetAfterRepo = buildTargetName.substring(slashIndex);
    }

    if (givenRepoName.isPresent() && givenRepoName.get().isEmpty()) {
      throw new BuildTargetParseException("Repo name must not be empty.");
    }

    if (!localToCanonicalRepoNamesMap.containsKey(givenRepoName)) {
      throw new HumanReadableException(String.format(
          "In build target '%s', repo '%s' is not defined.",
          buildTargetName,
          givenRepoName));
    }

    List<String> parts = BUILD_RULE_SEPARATOR_SPLITTER.splitToList(targetAfterRepo);
    if (parts.size() != 2) {
      throw new BuildTargetParseException(String.format(
          "%s must contain exactly one colon (found %d)", buildTargetName, parts.size() - 1));
    }

    String baseName =
        parts.get(0).isEmpty() ? buildTargetPatternParser.getBaseName() : parts.get(0);
    String shortName = parts.get(1);
    Iterable<String> flavorNames = new HashSet<>();
    int hashIndex = shortName.indexOf("#");
    if (hashIndex != -1 && hashIndex < shortName.length()) {
      flavorNames = flavorParser.parseFlavorString(shortName.substring(hashIndex + 1));
      shortName = shortName.substring(0, hashIndex);
    }

    Preconditions.checkNotNull(baseName);
    // On Windows, baseName may contain backslashes, which are not permitted by BuildTarget.
    baseName = baseName.replace("\\", "/");
    String fullyQualifiedName = baseName + ':' + shortName;
    if (!fullyQualifiedName.startsWith(BUILD_RULE_PREFIX)) {
      throw new BuildTargetParseException(
          String.format("%s must start with %s", fullyQualifiedName, BUILD_RULE_PREFIX));
    }

    ImmutableBuildTarget.Builder builder = BuildTarget.builder(baseName, shortName);
    for (String flavor : flavorNames) {
      builder.addFlavors(ImmutableFlavor.of(flavor));
    }
    Optional<String> canonicalRepoName = Preconditions.checkNotNull(
        localToCanonicalRepoNamesMap.get(givenRepoName));
    if (canonicalRepoName.isPresent()) {
      builder.setRepository(canonicalRepoName.get());
    }
    return builder.build();
  }
}
