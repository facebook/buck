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

package com.facebook.buck.features.python;

import com.facebook.buck.core.model.HasBuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Represents a {@link BuildRule} which contributes components to a top-level Python binary or test.
 */
public interface PythonPackagable extends HasBuildTarget {

  Iterable<BuildRule> getPythonPackageDeps(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder);

  /**
   * Python modules (i.e. sources, bytecode, or native extensions) associated with this rule.
   *
   * @return a map of modules, where the key is the module in {@link Path} form (including
   *     extension).
   */
  // TODO(agallagher): Separate out separate methods to access sources, bytecode, and native
  //  extensions independently of one another.
  default Optional<PythonMappedComponents> getPythonModules(
      @SuppressWarnings("unused") PythonPlatform pythonPlatform,
      @SuppressWarnings("unused") CxxPlatform cxxPlatform,
      @SuppressWarnings("unused") ActionGraphBuilder graphBuilder) {
    return Optional.empty();
  }

  /**
   * Resources (e.g. data files) associated with this rule.
   *
   * @return a map of native libraries, where the key is the soname wrapped as a {@link Path}.
   */
  // TODO(agallagher): Keys here ideally aren't `Path`s, as Buck's rule key calculations assume
  //  these paths exist and tries to hash them, which means we need to convert to strings
  //  beforehand.
  default Optional<PythonMappedComponents> getPythonResources(
      @SuppressWarnings("unused") PythonPlatform pythonPlatform,
      @SuppressWarnings("unused") CxxPlatform cxxPlatform,
      @SuppressWarnings("unused") ActionGraphBuilder graphBuilder) {
    return Optional.empty();
  }

  /**
   * Directories of extracted pre-built python libraries.
   *
   * @return a map where the values are {@link SourcePath}s of directories containing Python
   *     components (e.g. sources, native extensions, resources, etc) and the keys are the location
   *     where to link them in the final package.
   */
  // TODO(agallagher): The key here should be `String` representing the module location in the
  //  final package -- not `Path`.  That said, it looks like there aren't actually any cases that
  //  set the key to something other than the empty string, so we could probably just remove it.
  // TODO(agallagher): While this claims to be "module dirs" (which we define as python source or
  //  native extensions, this can actually probably include resources (and native libs?) too.  We
  //  should fix to handle these or, ideally, just avoid this in favor of pre-unpacking prebuilt
  //  python wheels and using `python_library()`.
  default Optional<PythonModuleDirComponents> getPythonModuleDirs() {
    return Optional.empty();
  }

  /**
   * @return whether the modules in this rule can be imported/run transparently from a Zip file
   *     (e.g. via zipimport). This is almost always the case, but in rare situations (e.g.
   *     execution expects to find packaged files in disk) rules can opt-out.
   */
  // TODO(agallagher): Can this be removed?  Things like extensions, native libraries, and resources
  //  already always need to be unpacked and in the case of the rare source that needs to be found
  //  on disk, this should really be a resource...
  default Optional<Boolean> isPythonZipSafe() {
    return Optional.empty();
  }

  /**
   * Allow this rule to opt-out it's transitive dependencies from omnibus linking. This is mainly
   * useful for the case of prebuilt python packages including prebuilt native extensions in their
   * sources parameter, which expect any native library dependencies to not be merged.
   *
   * @return whether this {@link PythonPackagable}'s transitive deps must be excluded from omnibus
   *     linking.
   */
  // TODO(agallagher): I think if made sure to model prebuilt python native extensions in a separate
  //  e.g. `prebuilt_python_extension()` rule, then we'd could forbid users from specifying them via
  //  `python_library(srcs=["extension.so"])` and no longer need this opt-out.
  default boolean doesPythonPackageDisallowOmnibus(
      @SuppressWarnings("unused") PythonPlatform pythonPlatform,
      @SuppressWarnings("unused") CxxPlatform cxxPlatform,
      @SuppressWarnings("unused") ActionGraphBuilder graphBuilder) {
    return false;
  }
}
