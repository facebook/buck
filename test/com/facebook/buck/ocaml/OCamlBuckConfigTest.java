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

package com.facebook.buck.ocaml;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxPlatformUtils;
import org.hamcrest.Matchers;
import org.junit.Test;

public class OCamlBuckConfigTest {

  @Test
  public void getCFlags() {
    OcamlBuckConfig config =
        new OcamlBuckConfig(
            FakeBuckConfig.builder().build(),
            CxxPlatformUtils.DEFAULT_PLATFORM
                .withCppflags("-cppflag")
                .withCflags("-cflag")
                .withAsflags("-asflag"));
    assertThat(config.getCFlags(), Matchers.contains("-cppflag", "-cflag", "-asflag"));
  }
}
