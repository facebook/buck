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

package com.facebook.buck.features.haskell;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

public class PrebuiltHaskellLibraryBuilder
    extends AbstractNodeBuilder<
        HaskellPrebuiltLibraryDescriptionArg.Builder, HaskellPrebuiltLibraryDescriptionArg,
        HaskellPrebuiltLibraryDescription, PrebuiltHaskellLibrary> {

  public PrebuiltHaskellLibraryBuilder(BuildTarget target) {
    super(new HaskellPrebuiltLibraryDescription(), target);
  }

  public PrebuiltHaskellLibraryBuilder setVersion(String version) {
    getArgForPopulating().setVersion(version);
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setId(String id) {
    getArgForPopulating().setId(id);
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setDb(SourcePath path) {
    getArgForPopulating().setDb(path);
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setImportDirs(ImmutableList<SourcePath> interfaces) {
    getArgForPopulating().setImportDirs(interfaces);
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setStaticLibs(ImmutableList<SourcePath> libs) {
    getArgForPopulating().setStaticLibs(libs);
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setSharedLibs(ImmutableMap<String, SourcePath> libs) {
    getArgForPopulating().setSharedLibs(libs);
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setExportedLinkerFlags(ImmutableList<String> flags) {
    getArgForPopulating().setExportedLinkerFlags(flags);
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setExportedCompilerFlags(ImmutableList<String> flags) {
    getArgForPopulating().setExportedCompilerFlags(flags);
    return this;
  }

  public PrebuiltHaskellLibraryBuilder setCxxHeaderDirs(
      ImmutableSortedSet<SourcePath> cxxHeaderDirs) {
    getArgForPopulating().setCxxHeaderDirs(cxxHeaderDirs);
    return this;
  }
}
