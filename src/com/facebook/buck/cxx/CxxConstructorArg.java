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
import com.facebook.buck.model.HasTests;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.SourceWithFlagsList;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

@SuppressFieldNotInitialized
public class CxxConstructorArg implements HasTests {

  public Optional<SourceWithFlagsList> srcs;
  public Optional<PatternMatchedCollection<SourceWithFlagsList>> platformSrcs;
  public Optional<SourceList> headers;
  public Optional<PatternMatchedCollection<SourceList>> platformHeaders;
  public Optional<ImmutableList<SourcePath>> prefixHeaders;
  public Optional<ImmutableList<String>> compilerFlags;
  public Optional<PatternMatchedCollection<ImmutableList<String>>> platformCompilerFlags;
  public Optional<ImmutableList<String>> preprocessorFlags;
  public Optional<PatternMatchedCollection<ImmutableList<String>>> platformPreprocessorFlags;
  public Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>> langPreprocessorFlags;
  public Optional<ImmutableList<String>> linkerFlags;
  public Optional<PatternMatchedCollection<ImmutableList<String>>> platformLinkerFlags;
  public Optional<ImmutableSet<Path>> frameworkSearchPaths;
  public Optional<ImmutableList<SourcePath>> lexSrcs;
  public Optional<ImmutableList<SourcePath>> yaccSrcs;
  public Optional<ImmutableSortedSet<BuildTarget>> deps;
  public Optional<String> headerNamespace;
  public Optional<Linker.CxxRuntimeType> cxxRuntimeType;

  @Hint(isDep = false) public Optional<ImmutableSortedSet<BuildTarget>> tests;

  @Override
  public ImmutableSortedSet<BuildTarget> getTests() {
    return tests.get();
  }

}
