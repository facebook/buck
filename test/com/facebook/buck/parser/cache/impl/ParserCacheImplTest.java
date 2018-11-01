/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser.cache.impl;

import static org.hamcrest.Matchers.not;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import java.io.File;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;

public class ParserCacheImplTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private static final String FOO_BAR_PATH = "Foo" + File.separator + "Bar";

  private ProjectFilesystem filesystem;

  private BuckConfig getBuckConfig(Path location) {
    FakeBuckConfig.Builder builder = FakeBuckConfig.builder();
    builder
        .setSections(
            "[" + ParserCacheConfig.PARSER_CACHE_SECTION_NAME + "]",
            ParserCacheConfig.PARSER_CACHE_LOCAL_LOCATION_NAME + " = " + location.toString(),
            "dir_mode = readwrite")
        .setFilesystem(filesystem);
    return builder.build();
  }

  @Before
  public void setUp() {
    // JimFS is not working on Windows with absolute and relative paths properly.
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/");
  }
}
