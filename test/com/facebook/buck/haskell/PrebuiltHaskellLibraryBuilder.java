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

package com.facebook.buck.haskell;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class PrebuiltHaskellLibraryBuilder
    extends AbstractNodeBuilder<PrebuiltHaskellLibraryDescription.Arg> {

  public PrebuiltHaskellLibraryBuilder(BuildTarget target) {
    super(
        new PrebuiltHaskellLibraryDescription(),
        target);
  }

  public PrebuiltHaskellLibraryBuilder setVersion(String version) {
    arg.version = version;
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setId(String id) {
    arg.id = Optional.of(id);
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setDb(SourcePath path) {
    arg.db = path;
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setImportDirs(ImmutableList<SourcePath> interfaces) {
    arg.importDirs = interfaces;
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setStaticLibs(ImmutableList<SourcePath> libs) {
    arg.staticLibs = libs;
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setSharedLibs(ImmutableMap<String, SourcePath> libs) {
    arg.sharedLibs = libs;
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setExportedLinkerFlags(ImmutableList<String> flags) {
    arg.exportedLinkerFlags = flags;
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setExportedCompilerFlags(ImmutableList<String> flags) {
    arg.exportedCompilerFlags = flags;
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setCxxHeaderDirs(
      ImmutableSortedSet<SourcePath> cxxHeaderDirs) {
    arg.cxxHeaderDirs = cxxHeaderDirs;
    return this;
  }

}
