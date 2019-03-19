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

package com.facebook.buck.cxx.toolchain;

import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxToolTypeInfererTest {

  @Test
  public void isVersionOfClang() {
    assertThat(
        CxxToolTypeInferer.getTypeFromVersionOutput(
            ImmutableList.of(
                "clang version 3.7.1 ", "Target: x86_64-unknown-linux-gnu", "Thread model: posix")),
        Matchers.is(CxxToolProvider.Type.CLANG));
    assertThat(
        CxxToolTypeInferer.getTypeFromVersionOutput(
            ImmutableList.of(
                "Apple LLVM version 7.0.2 (clang-700.1.81)",
                "Target: x86_64-apple-darwin15.3.0",
                "Thread model: posix")),
        Matchers.is(CxxToolProvider.Type.CLANG));
    assertThat(
        CxxToolTypeInferer.getTypeFromVersionOutput(
            ImmutableList.of(
                "gcc (GCC) 4.4.7 20120313 (Red Hat 4.4.7-16)",
                "Copyright (C) 2010 Free Software Foundation, Inc.",
                "This is free software; see the source for copying conditions.  There is NO",
                "warranty; not even for MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.")),
        Matchers.is(CxxToolProvider.Type.GCC));
    assertThat(
        CxxToolTypeInferer.getTypeFromVersionOutput(
            ImmutableList.of("Blah blah blah.", "I am a compiler.", "I am not clang though.")),
        Matchers.is(CxxToolProvider.Type.GCC));
  }
}
