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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.model.Pair;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

@SuppressFieldNotInitialized
public class CxxConstructorArg {
  public Optional<Either<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>> srcs;
  public Optional<Either<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>> headers;
  public Optional<ImmutableList<String>> compilerFlags;
  public Optional<ImmutableList<String>> preprocessorFlags;
  public Optional<ImmutableList<String>> linkerFlags;
  public Optional<ImmutableList<Pair<String, ImmutableList<String>>>> platformLinkerFlags;
  public Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>> langPreprocessorFlags;
  public Optional<ImmutableList<Path>> frameworkSearchPaths;
  public Optional<ImmutableList<SourcePath>> lexSrcs;
  public Optional<ImmutableList<SourcePath>> yaccSrcs;
  public Optional<ImmutableSortedSet<BuildTarget>> deps;
  public Optional<String> headerNamespace;
}
