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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public abstract class AbstractCxxSourceBuilder<
    T extends CxxConstructorArg,
    U extends Description<T>,
    V extends AbstractCxxSourceBuilder<T, U, V>>
    extends AbstractCxxBuilder<T, U> {

  public AbstractCxxSourceBuilder(
      U description,
      BuildTarget target) {
    super(description, target);
  }

  public V setSrcs(ImmutableSortedSet<SourceWithFlags> srcs)  {
    arg.srcs = srcs;
    return getThis();
  }

  public V setHeaders(ImmutableSortedSet<SourcePath> headers)  {
    arg.headers = SourceList.ofUnnamedSources(headers);
    return getThis();
  }

  public V setHeaders(ImmutableSortedMap<String, SourcePath> headers)  {
    arg.headers = SourceList.ofNamedSources(headers);
    return getThis();
  }

  public V setCompilerFlags(ImmutableList<String> compilerFlags) {
    arg.compilerFlags = compilerFlags;
    return getThis();
  }

  public V setPreprocessorFlags(ImmutableList<String> preprocessorFlags) {
    arg.preprocessorFlags = preprocessorFlags;
    return getThis();
  }

  public V setLinkerFlags(ImmutableList<String> linkerFlags) {
    arg.linkerFlags = linkerFlags;
    return getThis();
  }

  public V setPlatformCompilerFlags(
      PatternMatchedCollection<ImmutableList<String>> platformCompilerFlags) {
    arg.platformCompilerFlags = platformCompilerFlags;
    return getThis();
  }

  public V setPlatformPreprocessorFlags(
      PatternMatchedCollection<ImmutableList<String>> platformPreprocessorFlags) {
    arg.platformPreprocessorFlags = platformPreprocessorFlags;
    return getThis();
  }

  public V setPlatformLinkerFlags(
      PatternMatchedCollection<ImmutableList<String>> platformLinkerFlags) {
    arg.platformLinkerFlags = platformLinkerFlags;
    return getThis();
  }

  public V setFrameworks(ImmutableSortedSet<FrameworkPath> frameworks) {
    arg.frameworks = frameworks;
    return getThis();
  }

  public V setLibraries(ImmutableSortedSet<FrameworkPath> libraries) {
    arg.libraries = libraries;
    return getThis();
  }

  public V setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = deps;
    return getThis();
  }

  public V setHeaderNamespace(String namespace) {
    arg.headerNamespace = Optional.of(namespace);
    return getThis();
  }

  protected abstract V getThis();

}
