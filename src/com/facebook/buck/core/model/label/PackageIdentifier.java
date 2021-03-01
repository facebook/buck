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

// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import java.io.Serializable;
import java.util.Objects;
import javax.annotation.concurrent.Immutable;

/**
 * Uniquely identifies a package, given a repository name and a package's path fragment.
 *
 * <p>The repository the build is happening in is the <i>default workspace</i>, and is identified by
 * the workspace name "". Other repositories can be named in the WORKSPACE file. These workspaces
 * are prefixed by {@literal @}.
 */
@Immutable
public final class PackageIdentifier implements Comparable<PackageIdentifier>, Serializable {
  private static final Interner<PackageIdentifier> INTERNER = Interners.newWeakInterner();

  public static PackageIdentifier create(String repository, PathFragment pkgName)
      throws LabelSyntaxException {
    return create(RepositoryName.create(repository), pkgName);
  }

  public static PackageIdentifier create(RepositoryName repository, PathFragment pkgName) {
    // Note: We rely on these being interned to fast-path Label#equals.
    return INTERNER.intern(new PackageIdentifier(repository, pkgName));
  }

  /**
   * The identifier for this repository. This is either "" or prefixed with an "@", e.g., "@myrepo".
   */
  private final RepositoryName repository;

  /** The name of the package. */
  private final PathFragment pkgName;

  /**
   * Precomputed hash code. Hash/equality is based on repository and pkgName. Note that due to
   * interning, x.equals(y) <=> x==y.
   */
  private final int hashCode;

  private PackageIdentifier(RepositoryName repository, PathFragment pkgName) {
    this.repository = Preconditions.checkNotNull(repository);
    this.pkgName = Preconditions.checkNotNull(pkgName);
    this.hashCode = Objects.hash(repository, pkgName);
  }

  static PackageIdentifier parse(String input, String repo) throws LabelSyntaxException {
    String packageName;
    int packageStartPos = input.indexOf("//");
    if (repo != null) {
      packageName = input;
    } else if (input.startsWith("@") && packageStartPos > 0) {
      repo = input.substring(0, packageStartPos);
      packageName = input.substring(packageStartPos + 2);
    } else if (input.startsWith("@")) {
      throw new LabelSyntaxException("starts with a '@' but does not contain '//'");
    } else if (packageStartPos == 0) {
      repo = RepositoryName.DEFAULT_REPOSITORY;
      packageName = input.substring(2);
    } else {
      repo = RepositoryName.DEFAULT_REPOSITORY;
      packageName = input;
    }

    String error = RepositoryName.validate(repo);
    if (error != null) {
      throw new LabelSyntaxException(error);
    }

    error = LabelValidator.validatePackageName(packageName);
    if (error != null) {
      throw new LabelSyntaxException(error);
    }

    RepositoryName repositoryName = RepositoryName.create(repo);
    return create(repositoryName, PathFragment.create(packageName));
  }

  public RepositoryName getRepository() {
    return repository;
  }

  public PathFragment getPackageFragment() {
    return pkgName;
  }

  /**
   * Returns the name of this package.
   *
   * <p>There are certain places that expect the path fragment as the package name ('foo/bar') as a
   * package identifier. This isn't specific enough for packages in other repositories, so their
   * stringified version is '@baz//foo/bar'.
   */
  @Override
  public String toString() {
    return (repository.isDefault() || repository.isMain() ? "" : repository + "//") + pkgName;
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof PackageIdentifier)) {
      return false;
    }
    PackageIdentifier that = (PackageIdentifier) object;
    return this.hashCode == that.hashCode
        && pkgName.equals(that.pkgName)
        && repository.equals(that.repository);
  }

  @Override
  public int hashCode() {
    return this.hashCode;
  }

  @Override
  @SuppressWarnings("ReferenceEquality") // Performance optimization.
  public int compareTo(PackageIdentifier that) {
    // Fast-paths for the common case of the same package or a package in the same repository.
    if (this == that) {
      return 0;
    }
    if (repository == that.repository) {
      return pkgName.compareTo(that.pkgName);
    }
    return ComparisonChain.start()
        .compare(repository.toString(), that.repository.toString())
        .compare(pkgName, that.pkgName)
        .result();
  }
}
