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

package com.facebook.buck.features.python.toolchain.impl;

import static org.junit.Assert.assertThat;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PythonPlatformsProviderFactoryTest {

  @Test
  public void testDefaultPythonLibrary() {
    BuildTarget library = BuildTargetFactory.newInstance("//:library");
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("python", ImmutableMap.of("library", library.toString())))
            .build();
    assertThat(
        PythonPlatformsProviderFactoryUtils.getDefaultPythonPlatform(
                buckConfig,
                new FakeProcessExecutor(
                    Functions.constant(new FakeProcess(0, "CPython 2.7", "")), new TestConsole()),
                new AlwaysFoundExecutableFinder())
            .getCxxLibrary(),
        Matchers.equalTo(Optional.of(library)));
  }
}
