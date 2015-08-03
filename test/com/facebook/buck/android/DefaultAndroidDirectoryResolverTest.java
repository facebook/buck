/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.FakePropertyFinder;
import com.facebook.buck.util.PropertyFinder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class DefaultAndroidDirectoryResolverTest extends EasyMockSupport {
  @Rule
  public TemporaryPaths tmpDir = new TemporaryPaths();

  @Test
  public void testFindAndroidNdkDirEmpty() {
    PropertyFinder propertyFinder = new FakePropertyFinder(
        ImmutableMap.of(
            "ndk.dir", Optional.<Path>absent(),
            "ndk.repository", Optional.<Path>absent()));

    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            propertyFinder);

    assertEquals(Optional.<Path>absent(), androidDirectoryResolver.findAndroidNdkDir());
  }

  @Test
  public void testFindAndroidNdkDir() throws IOException {
    Path ndkDir = tmpDir.newFolder("ndk-dir");
    Path releaseFile = tmpDir.newFile("ndk-dir/RELEASE.TXT");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9d (64-bit)"), releaseFile);

    PropertyFinder propertyFinder = new FakePropertyFinder(
        ImmutableMap.of(
            "ndk.dir", Optional.of(ndkDir),
            "ndk.repository", Optional.<Path>absent()));

    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.of("r9d"),
            propertyFinder);

    assertEquals(Optional.of(ndkDir),
        androidDirectoryResolver.findAndroidNdkDir());
  }


  @Test
  public void testFindAndroidNdkDirAbsent() throws IOException {
    Path ndkDir = tmpDir.newFolder("ndk-dir");
    Path releaseFile = tmpDir.newFile("ndk-dir/RELEASE.TXT");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9e"), releaseFile);

    PropertyFinder propertyFinder = new FakePropertyFinder(
        ImmutableMap.of(
            "ndk.dir", Optional.of(ndkDir),
            "ndk.repository", Optional.<Path>absent()));

    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            propertyFinder);

    assertEquals(Optional.of(ndkDir),
        androidDirectoryResolver.findAndroidNdkDir());
  }

  @Test
  public void testFindAndroidNdkDirThrowOnFailedRead() throws IOException {
    Path ndkDir = tmpDir.newFolder("ndk-dir");

    PropertyFinder propertyFinder = new FakePropertyFinder(
        ImmutableMap.of(
            "ndk.dir", Optional.of(ndkDir),
            "ndk.repository", Optional.<Path>absent()));

    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            propertyFinder);

    try {
      androidDirectoryResolver.findAndroidNdkDir();
      fail("Should have thrown an exception for malformed NDK directory.");
    } catch (Exception e) {
      assertEquals("Failed to read NDK version from " + ndkDir.toAbsolutePath(),
          e.getMessage());
    }
  }

  @Test
  public void testFindAndroidNdkDirThrowOnUsupportedVersion() throws IOException {
    Path ndkDir = tmpDir.newFolder("ndk-dir");
    Path releaseFile = tmpDir.newFile("ndk-dir/RELEASE.TXT");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9q"), releaseFile);

    PropertyFinder propertyFinder = new FakePropertyFinder(
        ImmutableMap.of(
            "ndk.dir", Optional.of(ndkDir),
            "ndk.repository", Optional.<Path>absent()));

    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.of("r9e"),
            propertyFinder);

    try {
      androidDirectoryResolver.findAndroidNdkDir();
      fail("Should have thrown an exception for malformed NDK directory.");
    } catch (Exception e) {
      assertEquals("Supported NDK version is r9e but Buck is configured to use r9q with ndk.dir " +
            "or ANDROID_NDK",
          e.getMessage());
    }
  }

  @Test
  public void testFindAndroidNdkDirScanThrowsOnFail() throws IOException {
    tmpDir.newFolder("ndk-dir-r9a");
    tmpDir.newFolder("ndk-dir-r9b");
    tmpDir.newFolder("ndk-dir-r9c");
    Path releaseFile = tmpDir.newFile("ndk-dir-r9c/RELEASE.TXT");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9c"), releaseFile);

    PropertyFinder propertyFinder = new FakePropertyFinder(
        ImmutableMap.of(
            "ndk.dir", Optional.<Path>absent(),
            "ndk.repository", Optional.of(tmpDir.getRoot())));

    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.of("r9e"),
            propertyFinder);

    try {
      androidDirectoryResolver.findAndroidNdkDir();
      fail("Should have thrown an exception for malformed NDK directory.");
    } catch (Exception e) {
      assertEquals("Couldn't find a valid NDK under " + tmpDir.getRoot().toAbsolutePath(),
          e.getMessage());
    }
  }

  @Test
  public void testFindAndroidNdkDirScanTakesNewest() throws IOException {
    tmpDir.newFolder("ndk-dir-r9a");
    tmpDir.newFolder("ndk-dir-r9b");
    tmpDir.newFolder("ndk-dir-r9c");
    Path releaseFile = tmpDir.newFile("ndk-dir-r9a/RELEASE.TXT");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9a"), releaseFile);
    releaseFile = tmpDir.newFile("ndk-dir-r9b/RELEASE.TXT");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9b (64-bit)"), releaseFile);
    releaseFile = tmpDir.newFile("ndk-dir-r9c/RELEASE.TXT");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9c (64-bit)"), releaseFile);

    PropertyFinder propertyFinder = new FakePropertyFinder(
        ImmutableMap.of(
            "ndk.dir", Optional.<Path>absent(),
            "ndk.repository", Optional.of(tmpDir.getRoot())));

    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            propertyFinder);

    assertEquals(Optional.of(releaseFile.getParent()),
        androidDirectoryResolver.findAndroidNdkDir());
  }

  @Test
  public void notEqualWhenNdkIsDifferent() throws IOException {
    tmpDir.newFolder("ndk-dir-r9a");
    tmpDir.newFolder("ndk-dir-r9b");
    Path releaseFile = tmpDir.newFile("ndk-dir-r9a/RELEASE.TXT");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9a"), releaseFile);
    releaseFile = tmpDir.newFile("ndk-dir-r9b/RELEASE.TXT");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9b"), releaseFile);

    PropertyFinder propertyFinder = new FakePropertyFinder(
        ImmutableMap.of(
            "ndk.dir", Optional.<Path>absent(),
            "ndk.repository", Optional.of(tmpDir.getRoot())));

    DefaultAndroidDirectoryResolver androidDirectoryResolver1 =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.of("r9a"),
            propertyFinder);
    DefaultAndroidDirectoryResolver androidDirectoryResolver2 =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.of("r9b"),
            propertyFinder);

    assertNotEquals(androidDirectoryResolver1, androidDirectoryResolver2);
  }

  @Test
  public void testFindAndroidNdkDirScanTakesVersion() throws IOException {
    tmpDir.newFolder("ndk-dir-r9a");
    Path expectedPath = tmpDir.newFolder("ndk-dir-r9b");
    tmpDir.newFolder("ndk-dir-r9c");
    Path releaseFile = tmpDir.newFile("ndk-dir-r9a/RELEASE.TXT");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9a"), releaseFile);
    releaseFile = tmpDir.newFile("ndk-dir-r9b/RELEASE.TXT");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9b"), releaseFile);
    releaseFile = tmpDir.newFile("ndk-dir-r9c/RELEASE.TXT");
    MoreFiles.writeLinesToFile(ImmutableList.of("r9c"), releaseFile);

    PropertyFinder propertyFinder = new FakePropertyFinder(
        ImmutableMap.of(
            "ndk.dir", Optional.<Path>absent(),
            "ndk.repository", Optional.of(tmpDir.getRoot())));

    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.of("r9b"),
            propertyFinder);

    assertEquals(Optional.of(expectedPath),
        androidDirectoryResolver.findAndroidNdkDir());
  }
}
