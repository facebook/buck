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

import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PrebuiltCxxLibrary extends AbstractCxxLibrary {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final SourcePathResolver pathResolver;
  private final ImmutableList<Path> includeDirs;
  private final Path staticLibraryPath;
  private final Path sharedLibraryPath;
  private final String soname;
  private final boolean headerOnly;
  private final boolean linkWhole;
  private final boolean provided;

  public PrebuiltCxxLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      ImmutableList<Path> includeDirs,
      Path staticLibraryPath,
      Path sharedLibraryPath,
      String soname,
      boolean headerOnly,
      boolean linkWhole,
      boolean provided) {
    super(params, pathResolver);
    this.params = Preconditions.checkNotNull(params);
    this.ruleResolver = Preconditions.checkNotNull(ruleResolver);
    this.pathResolver = Preconditions.checkNotNull(pathResolver);
    this.includeDirs = Preconditions.checkNotNull(includeDirs);
    this.staticLibraryPath = Preconditions.checkNotNull(staticLibraryPath);
    this.sharedLibraryPath = Preconditions.checkNotNull(sharedLibraryPath);
    this.soname = Preconditions.checkNotNull(soname);
    this.headerOnly = headerOnly;
    this.linkWhole = linkWhole;
    this.provided = provided;
  }

  /**
   * Makes sure all build rules needed to produce the shared library are added to the action
   * graph.
   *
   * @return the {@link SourcePath} representing the actual shared library.
   */
  private SourcePath requireSharedLibrary() {

    // If the shared library is prebuilt, just return a reference to it.
    if (params.getProjectFilesystem().exists(sharedLibraryPath)) {
      return new PathSourcePath(sharedLibraryPath);
    }

    // Otherwise, generate it's build rule.
    BuildRule sharedLibrary =
        CxxDescriptionEnhancer.requireBuildRule(
            params,
            ruleResolver,
            CxxDescriptionEnhancer.SHARED_FLAVOR);

    return new BuildTargetSourcePath(sharedLibrary.getBuildTarget());
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput() {
    return CxxPreprocessorInput.builder()
        // Just pass the include dirs as system includes.
        .setSystemIncludeRoots(includeDirs)
        .build();
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      Linker linker, Type type) {

    // Build the library path and linker arguments that we pass through the
    // {@link NativeLinkable} interface for linking.
    ImmutableList.Builder<SourcePath> librariesBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> linkerArgsBuilder = ImmutableList.builder();
    if (!headerOnly) {
      if (provided || type == Type.SHARED) {
        SourcePath sharedLibrary = requireSharedLibrary();
        librariesBuilder.add(sharedLibrary);
        linkerArgsBuilder.add(pathResolver.getPath(sharedLibrary).toString());
      } else {
        librariesBuilder.add(new PathSourcePath(staticLibraryPath));
        if (linkWhole) {
          linkerArgsBuilder.addAll(linker.linkWhole(staticLibraryPath.toString()));
        } else {
          linkerArgsBuilder.add(staticLibraryPath.toString());
        }
      }
    }
    final ImmutableList<SourcePath> libraries = librariesBuilder.build();
    final ImmutableList<String> linkerArgs = linkerArgsBuilder.build();

    return new NativeLinkableInput(/* inputs */ libraries, /* args */ linkerArgs);
  }

  @Override
  public PythonPackageComponents getPythonPackageComponents() {

    // Build up the shared library list to contribute to a python executable package.
    ImmutableMap.Builder<Path, SourcePath> nativeLibrariesBuilder = ImmutableMap.builder();
    if (!headerOnly && !provided) {
      SourcePath sharedLibrary = requireSharedLibrary();
      nativeLibrariesBuilder.put(
          Paths.get(soname),
          sharedLibrary);
    }
    ImmutableMap<Path, SourcePath> nativeLibraries = nativeLibrariesBuilder.build();

    return new PythonPackageComponents(
        /* modules */ ImmutableMap.<Path, SourcePath>of(),
        /* resources */ ImmutableMap.<Path, SourcePath>of(),
        nativeLibraries);
  }

}
