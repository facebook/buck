/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

/** An action graph representation of an OCaml library. */
public abstract class OcamlLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps {

  public OcamlLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams) {
    super(buildTarget, projectFilesystem, buildRuleParams);
  }

  public abstract Path getIncludeLibDir(OcamlPlatform platform);

  public abstract Iterable<String> getBytecodeIncludeDirs(OcamlPlatform platform);

  /** Dependencies for the native (ocamlopt) build */
  public abstract ImmutableSortedSet<BuildRule> getNativeCompileDeps(OcamlPlatform platform);

  /** Dependencies for the bytecode (ocamlc) build */
  public abstract ImmutableSortedSet<BuildRule> getBytecodeCompileDeps(OcamlPlatform platform);

  public abstract ImmutableSortedSet<BuildRule> getBytecodeLinkDeps(OcamlPlatform platform);

  public abstract NativeLinkableInput getNativeLinkableInput(OcamlPlatform platform);

  public abstract NativeLinkableInput getBytecodeLinkableInput(OcamlPlatform platform);

  public abstract Iterable<BuildRule> getOcamlLibraryDeps(OcamlPlatform platform);
}
