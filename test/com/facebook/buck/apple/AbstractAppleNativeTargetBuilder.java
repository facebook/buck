/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

public abstract class AbstractAppleNativeTargetBuilder<
    ARG extends AppleNativeTargetDescriptionArg,
    BUILDER extends AbstractAppleNativeTargetBuilder<ARG, BUILDER>>
    extends AbstractNodeBuilder<ARG> {

  public AbstractAppleNativeTargetBuilder(
      Description<ARG> description,
      BuildTarget target) {
    super(description, target);
  }

  public BUILDER setConfigs(
      Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs) {
    arg.configs = configs;
    return getThis();
  }

  public BUILDER setCompilerFlags(Optional<ImmutableList<String>> compilerFlags) {
    arg.compilerFlags = compilerFlags;
    return getThis();
  }

  public BUILDER setPreprocessorFlags(Optional<ImmutableList<String>> preprocessorFlags) {
    arg.preprocessorFlags = preprocessorFlags;
    return getThis();
  }

  public BUILDER setExportedPreprocessorFlags(
      Optional<ImmutableList<String>> exportedPreprocessorFlags) {
    arg.exportedPreprocessorFlags = exportedPreprocessorFlags;
    return getThis();
  }

  public BUILDER setLinkerFlags(Optional<ImmutableList<String>> linkerFlags) {
    arg.linkerFlags = linkerFlags;
    return getThis();
  }

  public BUILDER setSrcs(Optional<ImmutableList<SourceWithFlags>> srcs) {
    arg.srcs = srcs;
    return getThis();
  }

  public BUILDER setExtraXcodeSources(Optional<ImmutableList<SourcePath>> extraXcodeSources) {
    arg.extraXcodeSources = extraXcodeSources;
    return getThis();
  }

  public BUILDER setHeaders(
      Optional<Either<
          ImmutableSortedSet<SourcePath>,
          ImmutableMap<String, SourcePath>>> headers) {
    arg.headers = headers;
    return getThis();
  }

  public BUILDER setHeaders(ImmutableSortedSet<SourcePath> headers) {
    return setHeaders(
        Optional.of(
            Either.<ImmutableSortedSet<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
                headers)));
  }

  public BUILDER setHeaders(ImmutableMap<String, SourcePath> headers) {
    return setHeaders(
        Optional.of(
            Either.<ImmutableSortedSet<SourcePath>, ImmutableMap<String, SourcePath>>ofRight(
                headers)));
  }

  public BUILDER setExportedHeaders(
      Optional<Either<
          ImmutableSortedSet<SourcePath>,
          ImmutableMap<String, SourcePath>>> exportedHeaders) {
    arg.exportedHeaders = exportedHeaders;
    return getThis();
  }

  public BUILDER setExportedHeaders(ImmutableSortedSet<SourcePath> exportedHeaders) {
    return setExportedHeaders(
        Optional.of(
            Either.<ImmutableSortedSet<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
                exportedHeaders)));
  }

  public BUILDER setExportedHeaders(ImmutableMap<String, SourcePath> exportedHeaders) {
    return setExportedHeaders(
        Optional.of(
            Either.<ImmutableSortedSet<SourcePath>, ImmutableMap<String, SourcePath>>ofRight(
                exportedHeaders)));
  }

  public BUILDER setFrameworks(Optional<ImmutableSortedSet<FrameworkPath>> frameworks) {
    arg.frameworks = frameworks;
    return getThis();
  }

  public BUILDER setDeps(Optional<ImmutableSortedSet<BuildTarget>> deps) {
    arg.deps = deps;
    return getThis();
  }

  public BUILDER setExportedDeps(Optional<ImmutableSortedSet<BuildTarget>> exportedDeps) {
    arg.exportedDeps = exportedDeps;
    return getThis();
  }

  public BUILDER setGid(Optional<String> gid) {
    arg.gid = gid;
    return getThis();
  }

  public BUILDER setHeaderPathPrefix(Optional<String> headerPathPrefix) {
    arg.headerPathPrefix = headerPathPrefix;
    return getThis();
  }

  public BUILDER setUseBuckHeaderMaps(Optional<Boolean> useBuckHeaderMaps) {
    arg.useBuckHeaderMaps = useBuckHeaderMaps;
    return getThis();
  }

  public BUILDER setPrefixHeader(Optional<SourcePath> prefixHeader) {
    arg.prefixHeader = prefixHeader;
    return getThis();
  }

  public BUILDER setTests(Optional<ImmutableSortedSet<BuildTarget>> tests) {
    arg.tests = tests;
    return getThis();
  }

  protected abstract BUILDER getThis();
}
