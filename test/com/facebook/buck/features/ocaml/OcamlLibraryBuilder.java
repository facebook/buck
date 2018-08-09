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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.google.common.collect.ImmutableSortedSet;

public class OcamlLibraryBuilder
    extends AbstractNodeBuilder<
        OcamlLibraryDescriptionArg.Builder,
        OcamlLibraryDescriptionArg,
        OcamlLibraryDescription,
        OcamlLibrary> {

  public OcamlLibraryBuilder(
      BuildTarget target, OcamlPlatform defaultPlatform, FlavorDomain<OcamlPlatform> platforms) {
    super(
        new OcamlLibraryDescription(
            new ToolchainProviderBuilder()
                .withToolchain(
                    OcamlToolchain.DEFAULT_NAME, OcamlToolchain.of(defaultPlatform, platforms))
                .build()),
        target);
  }

  public OcamlLibraryBuilder(BuildTarget target) {
    this(target, OcamlTestUtils.DEFAULT_PLATFORM, OcamlTestUtils.DEFAULT_PLATFORMS);
  }

  public OcamlLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public OcamlLibraryBuilder setPlatformDeps(
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> platformDeps) {
    getArgForPopulating().setPlatformDeps(platformDeps);
    return this;
  }
}
