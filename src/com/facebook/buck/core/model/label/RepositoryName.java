/*
 * Portions Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.facebook.buck.core.model.label;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.io.Serializable;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/** A human-readable name for the repository. */
public final class RepositoryName implements Serializable {
  static final String DEFAULT_REPOSITORY = "";
  private static final Pattern VALID_REPO_NAME = Pattern.compile("@[\\w\\-.]*");

  private static final LoadingCache<String, RepositoryName> repositoryNameCache =
      CacheBuilder.newBuilder()
          .weakValues()
          .build(
              new CacheLoader<String, RepositoryName>() {
                @Override
                public RepositoryName load(String name) throws LabelSyntaxException {
                  String errorMessage = validate(name);
                  if (errorMessage != null) {
                    errorMessage = "invalid repository name '" + name + "': " + errorMessage;
                    throw new LabelSyntaxException(errorMessage);
                  }
                  return new RepositoryName(name.intern());
                }
              });

  /**
   * Makes sure that name is a valid repository name and creates a new RepositoryName using it.
   *
   * @throws LabelSyntaxException if the name is invalid
   */
  public static RepositoryName create(String name) throws LabelSyntaxException {
    try {
      return repositoryNameCache.get(name);
    } catch (ExecutionException e) {
      Throwables.propagateIfPossible(e.getCause(), LabelSyntaxException.class);
      throw new IllegalStateException("Failed to create RepositoryName from " + name, e);
    }
  }

  /**
   * Creates a RepositoryName from a known-valid string (not @-prefixed). Generally this is a
   * directory that has been created via getSourceRoot() or getPathUnderExecRoot().
   */
  public static RepositoryName createFromValidStrippedName(CanonicalCellName name) {
    try {
      return repositoryNameCache.get("@" + name.getName());
    } catch (ExecutionException e) {
      throw new IllegalArgumentException(e.getMessage());
    }
  }

  private final String name;

  private RepositoryName(String name) {
    this.name = name;
  }

  /** Performs validity checking. Returns null on success, an error message otherwise. */
  static String validate(String name) {
    if (name.isEmpty()) {
      return null;
    }

    // Some special cases for more user-friendly error messages.
    if (!name.startsWith("@")) {
      return "workspace names must start with '@'";
    }
    if (name.equals("@.")) {
      return "workspace names are not allowed to be '@.'";
    }
    if (name.equals("@..")) {
      return "workspace names are not allowed to be '@..'";
    }

    if (!VALID_REPO_NAME.matcher(name).matches()) {
      return "workspace names may contain only A-Z, a-z, 0-9, '-', '_' and '.'";
    }

    return null;
  }

  /**
   * Returns the repository name without the leading "{@literal @}". For the default repository,
   * returns "".
   */
  public String strippedName() {
    if (name.isEmpty()) {
      return name;
    }
    return name.substring(1);
  }

  /**
   * Returns the repository name without the leading "{@literal @}". For the default repository,
   * returns "".
   */
  public static String stripName(String repoName) {
    return repoName.startsWith("@") ? repoName.substring(1) : repoName;
  }

  /** Returns if this is the default repository, that is, {@link #name} is "". */
  public boolean isDefault() {
    return name.isEmpty();
  }

  /** Returns if this is the main repository, that is, {@link #name} is "@". */
  public boolean isMain() {
    return name.equals("@");
  }

  /**
   * Returns the repository name, with leading "{@literal @}" (or "" for the default repository).
   */
  // TODO(bazel-team): Use this over toString()- easier to track its usage.
  public String getName() {
    return name;
  }

  /**
   * Returns the repository name, except that the main repo is conflated with the default repo
   * ({@code "@"} becomes the empty string).
   */
  public String getDefaultCanonicalForm() {
    return isMain() ? "" : getName();
  }

  /**
   * Returns the relative path to the repository source. Returns "" for the main repository and
   * external/[repository name] for external repositories.
   */
  public PathFragment getSourceRoot() {
    return isDefault() || isMain()
        ? PathFragment.EMPTY_FRAGMENT
        : LabelConstants.EXTERNAL_REPOSITORY_LOCATION.getRelative(strippedName());
  }

  /**
   * Returns the runfiles/execRoot path for this repository. If we don't know the name of this repo
   * (i.e., it is in the main repository), return an empty path fragment.
   *
   * <p>If --experimental_sibling_repository_layout is true, return "$execroot/../repo" (sibling of
   * __main__), instead of "$execroot/external/repo".
   */
  public PathFragment getExecPath(boolean siblingRepositoryLayout) {
    if (isDefault() || isMain()) {
      return PathFragment.EMPTY_FRAGMENT;
    }
    PathFragment prefix =
        siblingRepositoryLayout
            ? LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX
            : LabelConstants.EXTERNAL_PATH_PREFIX;
    return prefix.getRelative(strippedName());
  }

  /**
   * Returns the repository name, with leading "{@literal @}" (or "" for the default repository).
   */
  @Override
  public String toString() {
    return name;
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof RepositoryName)) {
      return false;
    }
    return OsPathPolicy.getFilePathOs().equals(name, ((RepositoryName) object).name);
  }

  @Override
  public int hashCode() {
    return OsPathPolicy.getFilePathOs().hash(name);
  }
}
