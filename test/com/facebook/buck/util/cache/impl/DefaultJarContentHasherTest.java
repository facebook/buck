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

package com.facebook.buck.util.cache.impl;

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.util.zip.CustomZipEntry;
import com.facebook.buck.util.zip.JarBuilder;
import com.facebook.buck.util.zip.JarEntrySupplier;
import com.facebook.buck.util.zip.ZipConstants;
import com.sun.jna.Platform;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.jar.JarFile;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DefaultJarContentHasherTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testGetContentHashesDoesNotIncorrectlyCache() throws Exception {
    if (Platform.isWindows()) {
      // Windows doesn't allow clobbering a file while it's already open, so this test isn't
      // meaningful for windows platforms
      return;
    }
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot().toPath());
    File toTest = temporaryFolder.newFile();
    File modification = temporaryFolder.newFile();
    new JarBuilder()
        .setShouldHashEntries(true)
        .addEntry(
            new JarEntrySupplier(
                new CustomZipEntry("Before"),
                "container_test",
                () -> new ByteArrayInputStream("Before".getBytes(StandardCharsets.UTF_8))))
        .createJarFile(toTest.toPath());
    new JarBuilder()
        .setShouldHashEntries(true)
        .addEntry(
            new JarEntrySupplier(
                new CustomZipEntry("After"),
                "container_test",
                () -> new ByteArrayInputStream("After".getBytes(StandardCharsets.UTF_8))))
        .createJarFile(modification.toPath());

    FileTime hardcodedTime = FileTime.fromMillis(ZipConstants.getFakeTime());
    Files.setLastModifiedTime(toTest.toPath(), hardcodedTime);
    Files.setLastModifiedTime(modification.toPath(), hardcodedTime);

    Path relativeToTestPath = temporaryFolder.getRoot().toPath().relativize(toTest.toPath());

    // Use JarBuilder with toTest -- we cache the open file to make sure it stays mmapped
    //noinspection unused
    JarFile cache = new JarFile(toTest);
    assertThat(
        new DefaultJarContentHasher(filesystem, relativeToTestPath).getContentHashes().keySet(),
        Matchers.contains(Paths.get("Before")));

    // Now modify toTest make sure we don't get a cached result when we open it for a second time
    Files.move(modification.toPath(), toTest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    Files.setLastModifiedTime(toTest.toPath(), hardcodedTime);
    assertThat(
        new DefaultJarContentHasher(filesystem, relativeToTestPath).getContentHashes().keySet(),
        Matchers.contains(Paths.get("After")));
    cache.close();
  }
}
