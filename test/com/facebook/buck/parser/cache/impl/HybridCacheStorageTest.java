/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser.cache.impl;

import static org.hamcrest.Matchers.not;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;

public class HybridCacheStorageTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private ProjectFilesystem filesystem;
  private BuckEventBus eventBus;

  private BuckConfig getConfig(String accessMode, Path path) {
    return FakeBuckConfig.builder()
        .setSections(
            "[parser]",
            "remote_parser_caching_access_mode = " + accessMode,
            "dir = " + path.toString(),
            "dir_mode = readwrite",
            "[project]",
            "Z = " + "Z",
            "Y = " + "Y",
            "[manifestservice]",
            "hybrid_thrift_endpoint=/hybrid_thrift",
            "slb_server_pool=https://buckcache-native.internal.tfbnw.net",
            "slb_timeout_millis=2000",
            "slb_max_acceptable_latency_millis=2000",
            "slb_ping_endpoint=/status.php",
            "slb_health_check_internal_millis=5000")
        .setFilesystem(filesystem)
        .build();
  }

  @Before
  public void setUp() {
    // JimFS is not working on Windows with absolute and relative paths properly.
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/");
    eventBus = BuckEventBusForTests.newInstance();
  }
}
