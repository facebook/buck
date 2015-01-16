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

package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

public class JavacStepTest {

  @Test
  public void findFailedImports() throws Exception {
    String lineSeperator = System.getProperty("line.separator");

    String stderrOutput = Joiner.on(lineSeperator).join(
        ImmutableList.of(
            "java/com/foo/bar.java:5: package javax.annotation.concurrent does not exist",
            "java/com/foo/bar.java:99: error: cannot access com.facebook.Raz",
            "java/com/foo/bar.java:142: cannot find symbol: class ImmutableSet",
            "java/com/foo/bar.java:999: you are a clown"));

    ImmutableSet<String> missingImports =
        JavacStep.findFailedImports(stderrOutput);
    assertEquals(
        ImmutableSet.of("javax.annotation.concurrent", "com.facebook.Raz", "ImmutableSet"),
        missingImports);
  }

}
