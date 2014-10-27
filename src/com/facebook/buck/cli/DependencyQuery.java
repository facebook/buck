/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class abstracts the data for a dependency query that the "buck query" command processes.
 * All queries reference a target. Queries that reference a source are queries for paths from
 * target back to source, and queries that don't reference a source are queries for all
 * dependencies of a target.
 */
public class DependencyQuery {

  /**
   * The ARROW_PATTERN is used for extracting the three components of a Buck query.
   * These are the target rule, the (optional) source rule, and the depth specifier inside
   * the dependency arrow. Target -depth> Source.
   * <p>
   * The target rule is mandatory so we use S+, while source rule is optional so it is specified
   * by S*. A nonexistent source rule will still be captured as an empty string in the third group
   * of the regex. Spaces are required between the build rules and the arrow, though a query
   * can also end immediately after the arrow. Inside the -?> arrow, users can specify either
   * a numeric depth \\d*, a single '*', or nothing (which is taken care of by the \\d* regex).
   */
  @VisibleForTesting
  static final Pattern ARROW_PATTERN = Pattern.compile(
      "\\s*(\\S+)\\s+" +
      "-(\\d*|\\*)>" +
      "(?:\\s+|$)(\\S*)\\s*");

  // TODO(user): Convert build rules from strings to BuildTargets
  private final String target;
  private final Optional<String> source;
  private final Optional<Integer> depth;

  private DependencyQuery(Optional<Integer> depth, String target) {
    this(depth, target, /* source */ Optional.<String>absent());
  }

  private DependencyQuery(Optional<Integer> depth, String target, Optional<String> source) {
    this.depth = depth;
    this.target = target;
    this.source = source;
  }

  String getTarget() {
    return target;
  }

  Optional<String> getSource() {
    return source;
  }

  Optional<Integer> getDepth() {
    return depth;
  }

  /**
   * This static factory method parses an input string query.
   * <p>
   * The general structure of a query is T -D> S.
   * T is the target, specified as a fully qualified target name. It is required.
   * S is the source, similarly fully qualified. When it is specified we assume you are
   *     querying for the dependency paths from T to S. When it is omitted we assume you are
   *     querying for all of the dependencies of T.
   * D is the depth. It controls the depth to which we will traverse the dependency graph
   *     starting at T. D=1 means we will search only for immediate dependencies. D=* means the
   *     depth will be unbounded. If D is omitted, as in "T -> S" then it defaults to *.
   * <p>
   * Example Queries:
   *     buck query '//:target -2>' finds all dependencies of dependencies of target
   *     buck query '//:target -*> //:source' finds all dependency paths from target to source
   *
   * @return dependency query we constructed based on string.
   * @throws HumanReadableException on bad query strings.
   */
  static DependencyQuery parseQueryString(String queryString,
      CommandLineBuildTargetNormalizer commandLineBuildTargetNormalizer)
          throws HumanReadableException {
    Matcher queryMatcher = ARROW_PATTERN.matcher(queryString);
    if (!queryMatcher.matches()) {
      throw new HumanReadableException(String.format("Invalid query string: %s.", queryString));
    }

    String arrowType = queryMatcher.group(2).trim();
    Optional<Integer> depth;
    if (arrowType.isEmpty() || arrowType.equals("*")) {
      depth = Optional.absent();
    } else {
      try {
        int numDepth = Integer.parseInt(arrowType);
        if (numDepth < 0) {
          throw new IllegalArgumentException(String.format("Negative depth: %d.", numDepth));
        }
        depth = Optional.of(numDepth);
      } catch (IllegalArgumentException e) {
        throw new HumanReadableException(e, String.format("Invalid search depth: %s.", arrowType));
      }
    }

    String target = queryMatcher.group(1).trim();
    final String fullyQualifiedTarget = commandLineBuildTargetNormalizer.normalize(target);

    String source = queryMatcher.group(3).trim();
    String fullyQualifiedSource = commandLineBuildTargetNormalizer.normalize(source);

    if (source.isEmpty()) {
      return new DependencyQuery(depth, fullyQualifiedTarget);
    } else {
      return new DependencyQuery(depth, fullyQualifiedTarget, Optional.of(fullyQualifiedSource));
    }
  }
}
