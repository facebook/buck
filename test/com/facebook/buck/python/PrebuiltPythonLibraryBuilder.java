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
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.ImmutableSortedSet;

public class PrebuiltPythonLibraryBuilder
    extends AbstractNodeBuilder<
        PrebuiltPythonLibraryDescriptionArg.Builder, PrebuiltPythonLibraryDescriptionArg,
        PrebuiltPythonLibraryDescription, PrebuiltPythonLibrary> {

  PrebuiltPythonLibraryBuilder(BuildTarget target) {
    super(new PrebuiltPythonLibraryDescription(), target);
  }

  public static PrebuiltPythonLibraryBuilder createBuilder(BuildTarget target) {
    return new PrebuiltPythonLibraryBuilder(target);
  }

  public PrebuiltPythonLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public PrebuiltPythonLibraryBuilder setBinarySrc(SourcePath binarySrc) {
    getArgForPopulating().setBinarySrc(binarySrc);
    return this;
  }

  public PrebuiltPythonLibraryBuilder setExcludeDepsFromMergedLinking(
      boolean excludeDepsFromOmnibus) {
    getArgForPopulating().setExcludeDepsFromMergedLinking(excludeDepsFromOmnibus);
    return this;
  }
}
