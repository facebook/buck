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

package com.facebook.buck.python.toolchain.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.python.toolchain.PythonVersion;
import com.facebook.buck.util.ProcessExecutor;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PythonVersionFactoryTest {

  @Test
  public void testGetPythonVersion() {
    PythonVersion version =
        PythonVersionFactory.extractPythonVersion(
            Paths.get("usr", "bin", "python"), new ProcessExecutor.Result(0, "", "CPython 2.7\n"));
    assertEquals("CPython 2.7", version.toString());
  }

  @Test
  public void testGetPyrunVersion() {
    PythonVersion version =
        PythonVersionFactory.extractPythonVersion(
            Paths.get("non", "important", "path"),
            new ProcessExecutor.Result(0, "", "CPython 2.7\n"));
    assertEquals("CPython 2.7", version.toString());
  }

  @Test
  public void testGetWindowsVersion() {
    String output = "CPython 2.7\r\n";
    PythonVersion version =
        PythonVersionFactory.extractPythonVersion(
            Paths.get("non", "important", "path"), new ProcessExecutor.Result(0, "", output));
    assertThat(version.toString(), Matchers.equalTo("CPython 2.7"));
  }

  @Test
  public void fromString() {
    assertThat(
        PythonVersionFactory.fromString("CPython 2.7"),
        Matchers.equalTo(PythonVersion.of("CPython", "2.7")));
  }
}
