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

package com.facebook.buck.shell;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class ShBinaryBuilder
    extends AbstractNodeBuilder<
        ShBinaryDescriptionArg.Builder, ShBinaryDescriptionArg, ShBinaryDescription, ShBinary> {

  public ShBinaryBuilder(BuildTarget target) {
    super(new ShBinaryDescription(), target);
  }

  public ShBinaryBuilder setMain(SourcePath path) {
    getArgForPopulating().setMain(path);
    return this;
  }

  public ShBinaryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public ShBinaryBuilder setResources(ImmutableSet<SourcePath> resources) {
    getArgForPopulating().setResources(resources);
    return this;
  }
}
