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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;

public class DefaultCxxPlatformsTest {

  @Test
  public void compilerFlagsPropagateToPreprocessorFlags() {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(
            new CxxBuckConfig(
                FakeBuckConfig.builder()
                    .setSections(
                        ImmutableMap.of(
                            "cxx",
                            ImmutableMap.of(
                                "cflags", "-std=gnu11",
                                "cppflags", "-DCFOO",
                                "cxxflags", "-std=c++11",
                                "cxxppflags", "-DCXXFOO")))
                    .build()));
    assertThat(cxxPlatform.getCflags(), containsInAnyOrder("-std=gnu11"));
    assertThat(cxxPlatform.getCppflags(), containsInAnyOrder("-DCFOO"));
    assertThat(cxxPlatform.getCxxflags(), containsInAnyOrder("-std=c++11"));
    assertThat(cxxPlatform.getCxxppflags(), containsInAnyOrder("-DCXXFOO"));
  }
}
