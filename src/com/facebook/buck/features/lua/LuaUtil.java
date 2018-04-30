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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.util.Collection;
import java.util.Optional;

public class LuaUtil {

  private LuaUtil() {}

  public static ImmutableMap<String, SourcePath> toModuleMap(
      BuildTarget target,
      SourcePathResolver resolver,
      String parameter,
      String baseModule,
      Iterable<SourceList> inputs) {

    ImmutableMap.Builder<String, SourcePath> moduleNamesAndSourcePaths = ImmutableMap.builder();

    for (SourceList input : inputs) {
      ImmutableMap<String, SourcePath> namesAndSourcePaths;
      if (input.getUnnamedSources().isPresent()) {
        namesAndSourcePaths =
            resolver.getSourcePathNames(target, parameter, input.getUnnamedSources().get());
      } else {
        namesAndSourcePaths = input.getNamedSources().get();
      }
      for (ImmutableMap.Entry<String, SourcePath> entry : namesAndSourcePaths.entrySet()) {
        String name = entry.getKey();
        if (!baseModule.isEmpty()) {
          name = baseModule + '/' + name;
        }
        moduleNamesAndSourcePaths.put(name, entry.getValue());
      }
    }

    return moduleNamesAndSourcePaths.build();
  }

  public static String getBaseModule(BuildTarget target, Optional<String> override) {
    return override.isPresent()
        ? override.get().replace('.', File.separatorChar)
        : target.getBasePath().toString();
  }

  public static ImmutableList<BuildTarget> getDeps(
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<BuildTarget> deps,
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> platformDeps) {
    return RichStream.<BuildTarget>empty()
        .concat(deps.stream())
        .concat(
            platformDeps
                .getMatchingValues(cxxPlatform.getFlavor().toString())
                .stream()
                .flatMap(Collection::stream))
        .toImmutableList();
  }
}
