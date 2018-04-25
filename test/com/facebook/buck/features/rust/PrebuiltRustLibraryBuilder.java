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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.google.common.collect.ImmutableSortedSet;

public class PrebuiltRustLibraryBuilder
    extends AbstractNodeBuilder<
        PrebuiltRustLibraryDescriptionArg.Builder, PrebuiltRustLibraryDescriptionArg,
        PrebuiltRustLibraryDescription, PrebuiltRustLibrary> {

  private PrebuiltRustLibraryBuilder(
      PrebuiltRustLibraryDescription description, BuildTarget target) {
    super(description, target);
  }

  public static PrebuiltRustLibraryBuilder from(String target) {
    return new PrebuiltRustLibraryBuilder(
        new PrebuiltRustLibraryDescription(), BuildTargetFactory.newInstance(target));
  }

  public PrebuiltRustLibraryBuilder setRlib(SourcePath rlib) {
    getArgForPopulating().setRlib(rlib);
    return this;
  }

  public PrebuiltRustLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public PrebuiltRustLibraryBuilder setPlatformDeps(
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> platformDeps) {
    getArgForPopulating().setPlatformDeps(platformDeps);
    return this;
  }
}
