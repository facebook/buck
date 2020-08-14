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

package com.facebook.buck.features.python;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class PexStepTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:target");
  private static final Path PYTHON_PATH = Paths.get("/usr/local/bin/python");
  private static final PythonVersion PYTHON_VERSION = PythonVersion.of("CPython", "2.6");
  private static final ImmutableMap<String, String> PEX_ENVIRONMENT = ImmutableMap.of();
  private static final ImmutableList<String> PEX_COMMAND = ImmutableList.of();
  private static final Path DEST_PATH = Paths.get("/dest");
  private static final String ENTRY_POINT = "entry_point.main";

  private final PythonResolvedPackageComponents COMPONENTS =
      ImmutablePythonResolvedPackageComponents.builder()
          .setModules(
              ImmutablePythonResolvedComponentsGroup.builder()
                  .setCanAccessComponentContents(true)
                  .putComponents(
                      TARGET,
                      new PythonMappedComponents.Resolved(
                          ImmutableSortedMap.of(
                              Paths.get("m"), AbsPath.of(Paths.get("/src/m").toAbsolutePath()))))
                  .putComponents(
                      TARGET,
                      new PythonModuleDirComponents.Resolved(
                          Paths.get("/tmp/dir1.whl").toAbsolutePath()))
                  .putComponents(
                      TARGET,
                      new PythonModuleDirComponents.Resolved(
                          Paths.get("/tmp/dir2.whl").toAbsolutePath()))
                  .build())
          .setResources(
              ImmutablePythonResolvedComponentsGroup.builder()
                  .setCanAccessComponentContents(true)
                  .putComponents(
                      TARGET,
                      new PythonMappedComponents.Resolved(
                          ImmutableSortedMap.of(
                              Paths.get("r"), AbsPath.of(Paths.get("/src/r").toAbsolutePath()))))
                  .build())
          .setNativeLibraries(
              ImmutablePythonResolvedComponentsGroup.builder()
                  .setCanAccessComponentContents(true)
                  .putComponents(
                      TARGET,
                      new PythonMappedComponents.Resolved(
                          ImmutableSortedMap.of(
                              Paths.get("n.so"),
                              AbsPath.of(Paths.get("/src/n.so").toAbsolutePath()))))
                  .build())
          .build();
  private final ImmutableSortedSet<String> PRELOAD_LIBRARIES = ImmutableSortedSet.of();
  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private final StepExecutionContext context = TestExecutionContext.newInstance();

  @Rule public TemporaryPaths tmpDir = new TemporaryPaths();

  @Test
  public void testCommandLine() {
    PexStep step =
        new PexStep(
            projectFilesystem,
            ProjectFilesystemUtils.relativize(
                projectFilesystem.getRootPath(), context.getBuildCellRootPath()),
            PEX_ENVIRONMENT,
            PEX_COMMAND,
            PYTHON_PATH,
            PYTHON_VERSION,
            DEST_PATH,
            ENTRY_POINT,
            COMPONENTS,
            PRELOAD_LIBRARIES,
            false);
    String command = Joiner.on(" ").join(step.getShellCommandInternal(context));

    assertThat(command, startsWith(Joiner.on(" ").join(PEX_COMMAND)));
    assertThat(command, containsString("--python " + PYTHON_PATH));
    assertThat(command, containsString("--python-version " + PYTHON_VERSION));
    assertThat(command, containsString("--entry-point " + ENTRY_POINT));
    assertThat(command, endsWith(" " + DEST_PATH));
  }

  @Test
  public void testCommandLineNoZipSafe() {
    PexStep step =
        new PexStep(
            projectFilesystem,
            ProjectFilesystemUtils.relativize(
                projectFilesystem.getRootPath(), context.getBuildCellRootPath()),
            PEX_ENVIRONMENT,
            PEX_COMMAND,
            PYTHON_PATH,
            PYTHON_VERSION,
            DEST_PATH,
            ENTRY_POINT,
            ImmutablePythonResolvedPackageComponents.builder()
                .from(COMPONENTS)
                .setZipSafe(false)
                .build(),
            PRELOAD_LIBRARIES,
            false);
    String command = Joiner.on(" ").join(step.getShellCommandInternal(context));

    assertThat(command, containsString("--no-zip-safe"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCommandStdin() throws IOException {
    Path realDir1 = tmpDir.getRoot().resolve("dir1.whl").getPath();
    Path realDir2 = tmpDir.getRoot().resolve("dir2.whl").getPath();
    Path file1 = realDir1.resolve("file1.py");
    Path file2 = realDir2.resolve("subdir").resolve("file2.py");
    Path childFile = realDir1.resolve("some_dir").resolve("child.py");

    Files.createDirectories(realDir1);
    Files.createDirectories(realDir2);
    Files.createDirectories(childFile.getParent());
    Files.write(file1, "print(\"file1\")".getBytes(StandardCharsets.UTF_8));
    Files.createDirectories(file2.getParent());
    Files.write(file2, "print(\"file2\")".getBytes(StandardCharsets.UTF_8));
    Files.write(childFile, "print(\"child\")".getBytes(StandardCharsets.UTF_8));

    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot());

    PexStep step =
        new PexStep(
            projectFilesystem,
            ProjectFilesystemUtils.relativize(
                projectFilesystem.getRootPath(), context.getBuildCellRootPath()),
            PEX_ENVIRONMENT,
            PEX_COMMAND,
            PYTHON_PATH,
            PYTHON_VERSION,
            DEST_PATH,
            ENTRY_POINT,
            ImmutablePythonResolvedPackageComponents.builder()
                .from(COMPONENTS)
                .setModules(
                    ImmutablePythonResolvedComponentsGroup.builder()
                        .setCanAccessComponentContents(true)
                        .setComponents(
                            ImmutableMultimap.of(
                                TARGET,
                                new PythonMappedComponents.Resolved(
                                    ImmutableSortedMap.of(
                                        Paths.get("m"),
                                        AbsPath.of(Paths.get("/src/m").toAbsolutePath()))),
                                TARGET,
                                new PythonModuleDirComponents.Resolved(realDir1),
                                TARGET,
                                new PythonModuleDirComponents.Resolved(realDir2)))
                        .build())
                .build(),
            PRELOAD_LIBRARIES,
            false);

    Map<String, Object> args = ObjectMappers.readValue(step.getStdin().orElse(""), Map.class);
    Assert.assertTrue(file1.isAbsolute());
    Assert.assertTrue(file2.isAbsolute());
    Assert.assertTrue(childFile.isAbsolute());
    assertThat(
        (Map<String, String>) args.get("modules"),
        hasEntry(Paths.get("m").toString(), Paths.get("/src/m").toAbsolutePath().toString()));
    assertThat(
        (Map<String, String>) args.get("modules"),
        hasEntry(Paths.get("file1.py").toString(), file1.toString()));
    assertThat(
        (Map<String, String>) args.get("modules"),
        hasEntry(Paths.get("some_dir", "child.py").toString(), childFile.toString()));
    assertThat(
        (Map<String, String>) args.get("modules"),
        hasEntry(Paths.get("subdir", "file2.py").toString(), file2.toString()));
    assertThat(
        (Map<String, String>) args.get("resources"),
        hasEntry(Paths.get("r").toString(), Paths.get("/src/r").toAbsolutePath().toString()));
    assertThat(
        (Map<String, String>) args.get("nativeLibraries"),
        hasEntry(Paths.get("n.so").toString(), Paths.get("/src/n.so").toAbsolutePath().toString()));
    assertEquals(0, ((List<String>) args.get("prebuiltLibraries")).size());
  }

  @Test
  public void testArgs() {
    PexStep step =
        new PexStep(
            projectFilesystem,
            ProjectFilesystemUtils.relativize(
                projectFilesystem.getRootPath(), context.getBuildCellRootPath()),
            PEX_ENVIRONMENT,
            ImmutableList.<String>builder().add("build").add("--some", "--args").build(),
            PYTHON_PATH,
            PYTHON_VERSION,
            DEST_PATH,
            ENTRY_POINT,
            COMPONENTS,
            PRELOAD_LIBRARIES,
            false);
    assertThat(step.getShellCommandInternal(context), hasItems("--some", "--args"));
  }
}
