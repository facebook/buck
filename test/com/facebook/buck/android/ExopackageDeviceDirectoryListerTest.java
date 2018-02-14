/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.*;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.Rule;
import org.junit.Test;

public class ExopackageDeviceDirectoryListerTest {
  @Rule public final TemporaryPaths tmpFolder = new TemporaryPaths();

  @Test
  public void deserializeDirectoryContentsForPackage() throws Exception {
    String device1 = "device1";
    String device2 = "device2";
    String package1 = "package1";
    String package2 = "package2";
    Path file1 = Paths.get("file1");
    Path file2 = Paths.get("file2");
    Path file3 = Paths.get("file3");
    // Note that this is package then device mapped (the opposite of how ExopackageDeviceLister does
    // it). This makes the code below simpler.
    ImmutableSortedMap<String, ImmutableSortedMap<String, ImmutableSortedSet<Path>>>
        contentsByPackageAndDevice =
            ImmutableSortedMap.of(
                package1,
                    ImmutableSortedMap.of(
                        device1,
                        ImmutableSortedSet.of(file1, file2),
                        device2,
                        ImmutableSortedSet.of(file3)),
                package2,
                    ImmutableSortedMap.of(
                        device1,
                        ImmutableSortedSet.of(file1, file2, file3),
                        device2,
                        ImmutableSortedSet.of()));

    SortedMap<String, SortedSet<String>> contents =
        ImmutableSortedMap.of(
            device1, new TreeSet<>(),
            device2, new TreeSet<>());
    for (String packageName : contentsByPackageAndDevice.keySet()) {
      for (String device : contentsByPackageAndDevice.get(packageName).keySet()) {
        contentsByPackageAndDevice
            .get(packageName)
            .get(device)
            .stream()
            .map(f -> Paths.get(packageName).resolve(f).toString())
            .forEach(f -> contents.get(device).add(f));
      }
    }

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpFolder.getRoot());
    Path contentsPath = Paths.get("contents");
    filesystem.writeContentsToPath(
        ExopackageDeviceDirectoryLister.serializeDirectoryContents(contents), contentsPath);

    assertEquals(
        contentsByPackageAndDevice.get(package1),
        ExopackageDeviceDirectoryLister.deserializeDirectoryContentsForPackage(
            filesystem, contentsPath, package1));

    assertEquals(
        contentsByPackageAndDevice.get(package2),
        ExopackageDeviceDirectoryLister.deserializeDirectoryContentsForPackage(
            filesystem, contentsPath, package2));
  }
}
