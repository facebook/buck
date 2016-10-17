/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.python;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class PythonLibraryBuilder extends AbstractNodeBuilder<PythonLibraryDescription.Arg> {

  public PythonLibraryBuilder(BuildTarget target) {
    super(new PythonLibraryDescription(), target);
  }

  public static PythonLibraryBuilder createBuilder(BuildTarget target) {
    return new PythonLibraryBuilder(target);
  }

  public PythonLibraryBuilder setSrcs(SourceList srcs) {
    arg.srcs = srcs;
    return this;
  }

  public PythonLibraryBuilder setPlatformSrcs(PatternMatchedCollection<SourceList> platformSrcs) {
    arg.platformSrcs = platformSrcs;
    return this;
  }

  public PythonLibraryBuilder setResources(SourceList resources) {
    arg.resources = resources;
    return this;
  }

  public PythonLibraryBuilder setPlatformResources(
      PatternMatchedCollection<SourceList> platformResources) {
    arg.platformResources = platformResources;
    return this;
  }

  public PythonLibraryBuilder setBaseModule(String baseModule) {
    arg.baseModule = Optional.of(baseModule);
    return this;
  }

  public PythonLibraryBuilder setZipSafe(boolean zipSafe) {
    arg.zipSafe = Optional.ofNullable(zipSafe);
    return this;
  }

  public PythonLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = deps;
    return this;
  }

}
