/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;

/** Factory class which produces a {@link BuildRule} that generates a shared library interface. */
public interface SharedLibraryInterfaceFactory {

  /** @return a rule building a shared library interface from an existing shared library. */
  BuildRule createSharedInterfaceLibraryFromLibrary(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      SourcePath library);

  /** @return a rule building a shared library interface from a shared link line */
  BuildRule createSharedInterfaceLibraryFromLinkableInput(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      String libName,
      Linker linker,
      ImmutableList<Arg> args);

  Iterable<BuildTarget> getParseTimeDeps();
}
