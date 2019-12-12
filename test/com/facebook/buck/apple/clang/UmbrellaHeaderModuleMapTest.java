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

package com.facebook.buck.apple.clang;

import static com.facebook.buck.util.MoreStringsForTests.equalToIgnoringPlatformNewlines;
import static org.junit.Assert.assertThat;

import org.junit.Test;

public class UmbrellaHeaderModuleMapTest {

  @Test
  public void testNoSwift() {
    UmbrellaHeaderModuleMap testMap =
        new UmbrellaHeaderModuleMap("TestModule", UmbrellaHeaderModuleMap.SwiftMode.NO_SWIFT);
    assertThat(
        "module TestModule {\n"
            + "    umbrella header \"TestModule.h\"\n"
            + "\n"
            + "    export *\n"
            + "    module * { export * }\n"
            + "}\n"
            + "\n",
        equalToIgnoringPlatformNewlines(testMap.render()));
  }

  @Test
  public void testIncludeSwift() {
    UmbrellaHeaderModuleMap testMap =
        new UmbrellaHeaderModuleMap(
            "TestModule", UmbrellaHeaderModuleMap.SwiftMode.INCLUDE_SWIFT_HEADER);
    assertThat(
        "module TestModule {\n"
            + "    umbrella header \"TestModule.h\"\n"
            + "\n"
            + "    export *\n"
            + "    module * { export * }\n"
            + "}\n"
            + "\n"
            + "module TestModule.Swift {\n"
            + "    header \"TestModule-Swift.h\"\n"
            + "    requires objc\n"
            + "}"
            + "\n",
        equalToIgnoringPlatformNewlines(testMap.render()));
  }

  @Test
  public void testExcludeSwift() {
    UmbrellaHeaderModuleMap testMap =
        new UmbrellaHeaderModuleMap(
            "TestModule", UmbrellaHeaderModuleMap.SwiftMode.EXCLUDE_SWIFT_HEADER);
    assertThat(
        "module TestModule {\n"
            + "    umbrella header \"TestModule.h\"\n"
            + "\n"
            + "    export *\n"
            + "    module * { export * }\n"
            + "}\n"
            + "\n"
            + "module TestModule.__Swift {\n"
            + "    exclude header \"TestModule-Swift.h\"\n"
            + "}"
            + "\n",
        equalToIgnoringPlatformNewlines(testMap.render()));
  }
}
