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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;

public class DefaultClassUsageFileReaderTest {

  @Test
  public void testNotUltralightOnlyDependencyTooManyValues() {
    assertFalse(
        DefaultClassUsageFileReader.isUltralightOnlyDependency(
            ImmutableMap.of("A", 1, "B", 2, "C", 3)));
  }

  @Test
  public void testNotUltralightOnlyDependencyWrongName() {
    assertFalse(
        DefaultClassUsageFileReader.isUltralightOnlyDependency(
            ImmutableMap.of("_STRIPPED_RESOURCES/ultralight/", 1)));
  }

  @Test
  public void testUltralightOnlyDependencyFirstString() {
    assertTrue(
        DefaultClassUsageFileReader.isUltralightOnlyDependency(
            ImmutableMap.of("_STRIPPED_RESOURCES/ultralight/modules/module.ultm", 1, "2", 1)));
  }

  @Test
  public void testUltralightOnlyDependencySecondString() {
    assertTrue(
        DefaultClassUsageFileReader.isUltralightOnlyDependency(
            ImmutableMap.of("1", 1, "_STRIPPED_RESOURCES/ultralight/modules/module.ultm", 1)));
  }

  @Test
  public void testUltralightOnlyDependencyWrongCountForUltm() {
    assertFalse(
        DefaultClassUsageFileReader.isUltralightOnlyDependency(
            ImmutableMap.of("1", 1, "_STRIPPED_RESOURCES/ultralight/modules/module.ultm", 2)));
  }

  @Test
  public void testUltralightOnlyDependencyWrongCountForOtherClass() {
    assertFalse(
        DefaultClassUsageFileReader.isUltralightOnlyDependency(
            ImmutableMap.of("1", 4, "_STRIPPED_RESOURCES/ultralight/modules/module.ultm", 1)));
  }
}
