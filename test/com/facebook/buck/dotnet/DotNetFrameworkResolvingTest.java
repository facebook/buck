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

package com.facebook.buck.dotnet;

import static com.facebook.buck.dotnet.FrameworkVersion.NET35;
import static com.facebook.buck.dotnet.FrameworkVersion.NET46;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

public class DotNetFrameworkResolvingTest {

  private FileSystem filesystem;
  private Path baseFrameworkDir;
  private String programFiles;

  @Before
  public void setUpFilesystem() throws IOException {
    filesystem = Jimfs.newFileSystem(Configuration.windows());

    programFiles = "c:\\Program Files(x86)";

    baseFrameworkDir = filesystem.getPath(programFiles)
        .resolve("Reference Assemblies")
        .resolve("Microsoft")
        .resolve("Framework");
    Files.createDirectories(baseFrameworkDir);
  }

  @Test
  public void shouldFindDotNet35Directory() throws IOException {
    ImmutableMap<String, String> env = ImmutableMap.of("ProgramFiles(x86)", programFiles);
    Path baseDir = baseFrameworkDir.resolve("v3.5");
    Files.createDirectories(baseDir);
    Path expected = baseDir.resolve("cake.dll");
    Files.write(expected, "".getBytes(UTF_8));

    DotNetFramework framework = DotNetFramework.resolveFramework(filesystem, env, NET35);
    Path actual = framework.findReferenceAssembly("cake.dll");

    assertEquals(expected, actual);
  }

  @Test
  public void shouldFindVersionOfDotNetFrameworkGreaterThan35() throws IOException {
    ImmutableMap<String, String> env = ImmutableMap.of("ProgramFiles(x86)", programFiles);
    Path baseDir = baseFrameworkDir.resolve(".NETFramework").resolve("v4.6");
    Files.createDirectories(baseDir);
    Path expected = baseDir.resolve("cake.dll");
    Files.write(expected, "".getBytes(UTF_8));

    DotNetFramework framework = DotNetFramework.resolveFramework(filesystem, env, NET46);
    Path actual = framework.findReferenceAssembly("cake.dll");

    assertEquals(expected, actual);
  }

  @Test(expected = HumanReadableException.class)
  public void shouldThrowAnExceptionIfNoFrameworkCanBeFound() throws IOException {
    ImmutableMap<String, String> env = ImmutableMap.of("ProgramFiles(x86)", programFiles);
    Path baseDir = baseFrameworkDir.resolve(".NETFramework").resolve("v4.6");
    Files.createDirectories(baseDir);

    DotNetFramework.resolveFramework(filesystem, env, NET35);
  }

  @Test(expected = HumanReadableException.class)
  public void shouldThrowAnExceptionIfTheFrameworkDirectoryIsFoundButIsNotADirectory()
      throws IOException {
    ImmutableMap<String, String> env = ImmutableMap.of("ProgramFiles(x86)", programFiles);
    Path baseDir = baseFrameworkDir.resolve(".NETFramework").resolve("v4.6");
    Files.createDirectories(baseDir.getParent());
    Files.write(baseDir, "".getBytes(UTF_8));

    DotNetFramework.resolveFramework(filesystem, env, NET46);
  }
}
