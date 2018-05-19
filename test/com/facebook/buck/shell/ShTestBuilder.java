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

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

public class ShTestBuilder
    extends AbstractNodeBuilder<
        ShTestDescriptionArg.Builder, ShTestDescriptionArg, ShTestDescription, ShTest> {

  public ShTestBuilder(BuildTarget target) {
    super(new ShTestDescription(FakeBuckConfig.builder().build()), target);
  }

  public ShTestBuilder setTest(SourcePath path) {
    getArgForPopulating().setTest(path);
    return this;
  }

  public ShTestBuilder setArgs(ImmutableList<StringWithMacros> args) {
    getArgForPopulating().setArgs(args);
    return this;
  }

  public ShTestBuilder setEnv(ImmutableMap<String, StringWithMacros> env) {
    getArgForPopulating().setEnv(env);
    return this;
  }

  public ShTestBuilder setResources(ImmutableSortedSet<Path> resources) {
    getArgForPopulating().setResources(resources);
    return this;
  }
}
