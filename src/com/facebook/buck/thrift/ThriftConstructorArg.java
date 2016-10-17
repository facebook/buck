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

package com.facebook.buck.thrift;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.SourceWithFlagsList;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

@SuppressFieldNotInitialized
public class ThriftConstructorArg extends AbstractDescriptionArg {

  public String name;
  public ImmutableMap<SourcePath, ImmutableList<String>> srcs;
  public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();

  public ImmutableList<String> flags = ImmutableList.of();

  public ImmutableSet<String> javaOptions = ImmutableSet.of();

  public Optional<String> cppHeaderNamespace;
  public SourceList cppExportedHeaders = SourceList.EMPTY;
  public SourceWithFlagsList cppSrcs = SourceWithFlagsList.EMPTY;
  public ImmutableSortedSet<BuildTarget> cppDeps = ImmutableSortedSet.of();
  public ImmutableSortedSet<BuildTarget> cpp2Deps = ImmutableSortedSet.of();
  public ImmutableList<String> cppCompilerFlags = ImmutableList.of();
  public ImmutableList<String> cpp2CompilerFlags = ImmutableList.of();
  public ImmutableSet<String> cppOptions = ImmutableSet.of();
  public ImmutableSet<String> cpp2Options = ImmutableSet.of();

  public ImmutableSet<String> pyOptions = ImmutableSet.of();
  public Optional<String> pyBaseModule;
  public Optional<String> pyTwistedBaseModule;
  public Optional<String> pyAsyncioBaseModule;

}
