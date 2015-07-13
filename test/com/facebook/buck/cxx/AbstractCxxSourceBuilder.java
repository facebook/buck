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
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.rules.coercer.SourceWithFlagsList;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class AbstractCxxSourceBuilder<T extends CxxConstructorArg> extends AbstractCxxBuilder<T> {

  public AbstractCxxSourceBuilder(
      Description<T> description,
      BuildTarget target) {
    super(description, target);
  }

  public AbstractCxxSourceBuilder<T> setSrcs(ImmutableList<SourceWithFlags> srcs)  {
    arg.srcs = Optional.of(SourceWithFlagsList.ofUnnamedSources(srcs));
    return this;
  }

  public AbstractCxxSourceBuilder<T> setSrcs(ImmutableMap<String, SourceWithFlags> srcs)  {
    arg.srcs = Optional.of(SourceWithFlagsList.ofNamedSources(srcs));
    return this;
  }

  public AbstractCxxSourceBuilder<T> setSrcs(SourceWithFlagsList srcs)  {
    arg.srcs = Optional.of(srcs);
    return this;
  }

  public AbstractCxxSourceBuilder<T> setHeaders(ImmutableSortedSet<SourcePath> headers)  {
    arg.headers = Optional.of(SourceList.ofUnnamedSources(headers));
    return this;
  }

  public AbstractCxxSourceBuilder<T> setHeaders(ImmutableMap<String, SourcePath> headers)  {
    arg.headers = Optional.of(SourceList.ofNamedSources(headers));
    return this;
  }

  public AbstractCxxSourceBuilder<T> setHeaders(SourceList headers)  {
    arg.headers = Optional.of(headers);
    return this;
  }

  public AbstractCxxSourceBuilder<T> setCompilerFlags(ImmutableList<String> compilerFlags) {
    arg.compilerFlags = Optional.of(compilerFlags);
    return this;
  }

  public AbstractCxxSourceBuilder<T> setPreprocessorFlags(ImmutableList<String> preprocessorFlags) {
    arg.preprocessorFlags = Optional.of(preprocessorFlags);
    return this;
  }

  public AbstractCxxSourceBuilder<T> setLinkerFlags(ImmutableList<String> linkerFlags) {
    arg.linkerFlags = Optional.of(linkerFlags);
    return this;
  }

  public AbstractCxxSourceBuilder<T> setFrameworkSearchPaths(
      ImmutableSet<Path> frameworkSearchPaths) {
    arg.frameworkSearchPaths = Optional.of(frameworkSearchPaths);
    return this;
  }

  public AbstractCxxSourceBuilder<T> setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = Optional.of(deps);
    return this;
  }

  public AbstractCxxSourceBuilder<T> setHeaderNamespace(String namespace) {
    arg.headerNamespace = Optional.of(namespace);
    return this;
  }

}
