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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Paths;

public class AppleDescriptionsTest {

  @Test
  public void convertToFlatCxxHeadersWithPrefix() {
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            "prefix/some_file.h", new TestSourcePath("path/to/some_file.h"),
            "prefix/another_file.h", new TestSourcePath("path/to/another_file.h"),
            "prefix/a_file.h", new TestSourcePath("different/path/to/a_file.h"),
            "prefix/file.h", new TestSourcePath("file.h")),
        AppleDescriptions.convertToFlatCxxHeaders(
            Paths.get("prefix"),
            new SourcePathResolver(new BuildRuleResolver()),
            ImmutableSet.<SourcePath>of(
                new TestSourcePath("path/to/some_file.h"),
                new TestSourcePath("path/to/another_file.h"),
                new TestSourcePath("different/path/to/a_file.h"),
                new TestSourcePath("file.h"))));
  }

  @Test
  public void convertToFlatCxxHeadersWithoutPrefix() {
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            "some_file.h", new TestSourcePath("path/to/some_file.h"),
            "another_file.h", new TestSourcePath("path/to/another_file.h"),
            "a_file.h", new TestSourcePath("different/path/to/a_file.h"),
            "file.h", new TestSourcePath("file.h")),
        AppleDescriptions.convertToFlatCxxHeaders(
            Paths.get(""),
            new SourcePathResolver(new BuildRuleResolver()),
            ImmutableSet.<SourcePath>of(
                new TestSourcePath("path/to/some_file.h"),
                new TestSourcePath("path/to/another_file.h"),
                new TestSourcePath("different/path/to/a_file.h"),
                new TestSourcePath("file.h"))));
  }

}
