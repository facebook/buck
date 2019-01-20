/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.features.js;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.test.selectors.Nullable;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class JsLibraryBuilder
    extends AbstractNodeBuilder<
        JsLibraryDescriptionArg.Builder, JsLibraryDescriptionArg, JsLibraryDescription, JsLibrary> {
  private static final JsLibraryDescription libraryDescription = new JsLibraryDescription();

  JsLibraryBuilder(BuildTarget target, ProjectFilesystem filesystem) {
    super(libraryDescription, target, filesystem);
  }

  JsLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  JsLibraryBuilder setDepsQuery(@Nullable Query query) {
    getArgForPopulating().setDepsQuery(Optional.ofNullable(query));
    return this;
  }

  JsLibraryBuilder setExtraJson(Optional<StringWithMacros> extraJson) {
    getArgForPopulating().setExtraJson(extraJson);
    return this;
  }

  JsLibraryBuilder setSrcs(ImmutableSet<Either<SourcePath, Pair<SourcePath, String>>> srcs) {
    getArgForPopulating().setSrcs(srcs);
    return this;
  }

  JsLibraryBuilder setBasePath(@Nullable String basePath) {
    getArgForPopulating().setBasePath(Optional.ofNullable(basePath));
    return this;
  }

  JsLibraryBuilder setWorker(BuildTarget worker) {
    getArgForPopulating().setWorker(worker);
    return this;
  }
}
