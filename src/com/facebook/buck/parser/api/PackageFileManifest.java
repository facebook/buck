/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.parser.api;

import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.parser.exceptions.ParsingError;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * Describes the content of a package file, which includes a package definition and their metadata.
 */
@BuckStyleValue
public abstract class PackageFileManifest implements ComputeResult, FileManifest {

  /** A singleton instance of a manifest with an empty package metadata. */
  public static final PackageFileManifest EMPTY_SINGLETON =
      ImmutablePackageFileManifest.of(
          PackageMetadata.EMPTY_SINGLETON,
          ImmutableSet.of(),
          ImmutableMap.of(),
          ImmutableList.of());

  /** Contains the package defined in the build file. */
  public abstract PackageMetadata getPackage();

  @Override
  public abstract ImmutableSet<String> getIncludes();

  @Override
  public abstract ImmutableMap<String, Object> getReadConfigurationOptionsForTest();

  @Override
  public abstract ImmutableList<ParsingError> getErrors();

  public static PackageFileManifest of(
      PackageMetadata getPackage,
      ImmutableSet<String> includes,
      ImmutableMap<String, Object> configs,
      ImmutableList<ParsingError> errors) {
    return ImmutablePackageFileManifest.ofImpl(getPackage, includes, configs, errors);
  }
}
