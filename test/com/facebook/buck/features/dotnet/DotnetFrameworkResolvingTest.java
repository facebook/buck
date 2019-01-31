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

package com.facebook.buck.features.dotnet;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;

public class DotnetFrameworkResolvingTest {

  private FileSystem filesystem;

  @Before
  public void setUpFilesystem() {
    filesystem = Jimfs.newFileSystem(Configuration.windows());
  }

  @Test
  public void shouldFindDotnet35Directory() throws IOException {
    createFrameworkDirs(FrameworkVersion.NET35);
    Path baseDir = filesystem.getPath(FrameworkVersion.NET35.getDirectories().get(0));
    Files.createDirectories(baseDir);
    Path expected = baseDir.resolve("cake.dll");
    Files.write(expected, new byte[10]);

    DotnetFramework framework =
        DotnetFramework.resolveFramework(filesystem, FrameworkVersion.NET35);
    Path actual = framework.findReferenceAssembly("cake.dll");

    assertEquals(expected, actual);
  }

  @Test
  public void shouldFindVersionOfDotnetFrameworkGreaterThan35() throws IOException {
    createFrameworkDirs(FrameworkVersion.NET46);
    Path baseDir = filesystem.getPath(FrameworkVersion.NET46.getDirectories().get(0));
    Path expected = baseDir.resolve("cake.dll");
    Files.write(expected, new byte[10]);
    DotnetFramework framework =
        DotnetFramework.resolveFramework(filesystem, FrameworkVersion.NET46);
    Path actual = framework.findReferenceAssembly("cake.dll");
    assertEquals(expected, actual);
  }

  @Test(expected = HumanReadableException.class)
  public void shouldThrowAnExceptionIfNoFrameworkCanBeFound() throws IOException {
    createFrameworkDirs(FrameworkVersion.NET46);
    DotnetFramework.resolveFramework(filesystem, FrameworkVersion.NET35);
  }

  @Test(expected = HumanReadableException.class)
  public void shouldThrowAnExceptionIfTheFrameworkDirectoryIsFoundButIsNotADirectory()
      throws IOException {
    Path baseDir = filesystem.getPath(FrameworkVersion.NET46.getDirectories().get(0));
    Files.createDirectories(baseDir.getParent());
    Files.write(baseDir, new byte[10]);
    DotnetFramework.resolveFramework(filesystem, FrameworkVersion.NET46);
  }

  private void createFrameworkDirs(FrameworkVersion frameworkVersion) throws IOException {
    for (String dir : frameworkVersion.getDirectories()) {
      Files.createDirectories(filesystem.getPath(dir));
    }
  }
}
