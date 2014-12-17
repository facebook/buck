/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.google.common.base.Optional;

public class CxxLibraryBuilder extends AbstractCxxSourceBuilder<CxxLibraryDescription.Arg> {

  public CxxLibraryBuilder(
      BuildTarget target,
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(new CxxLibraryDescription(cxxBuckConfig, cxxPlatforms), target);
  }

  public CxxLibraryBuilder(BuildTarget target) {
    this(target, createDefaultConfig(), createDefaultPlatforms());
  }

  public CxxLibraryBuilder setSoname(String soname) {
    arg.soname = Optional.of(soname);
    return this;
  }

  public CxxLibraryBuilder setLinkWhole(boolean linkWhole) {
    arg.linkWhole = Optional.of(linkWhole);
    return this;
  }

}
