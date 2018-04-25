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

package com.facebook.buck.features.d;

import static org.junit.Assert.assertThat;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.model.BuildTargetFactory;
import com.google.common.collect.ImmutableSortedSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class DBinaryDescriptionTest {

  @Test
  public void cxxLinkerInImplicitTimeDeps() {
    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;
    DBinaryBuilder builder =
        new DBinaryBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            new DBuckConfig(FakeBuckConfig.builder().build()),
            cxxPlatform);
    ImmutableSortedSet<BuildTarget> implicitDeps = builder.findImplicitDeps();
    for (BuildTarget target : cxxPlatform.getLd().getParseTimeDeps()) {
      assertThat(implicitDeps, Matchers.hasItem(target));
    }
  }
}
