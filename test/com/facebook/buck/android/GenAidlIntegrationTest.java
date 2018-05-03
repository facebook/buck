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
package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GenAidlIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public TemporaryPaths tmp2 = new TemporaryPaths();

  @Test
  public void buildingWithAidlSrcsDeclared() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "gen_aidl_missing_src", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    ProcessResult result = workspace.runBuckBuild("//:android-lib");
    result.assertSuccess();

    try {
      workspace.runBuckBuild("//:AServiceWithMissingDependency");
      Assert.fail("An exception should've been thrown.");
    } catch (HumanReadableException e) {
      String msg = e.toString();
      assertTrue("Received: " + msg, msg.contains("MyMissingDependency.aidl"));
    }
  }

  @Test
  public void buildingCleaningAndThenRebuildingFromCacheShouldWorkAsExpected()
      throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cached_build", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    // Populate the cache
    ProcessResult result = workspace.runBuckBuild("//:android-lib");
    result.assertSuccess();
    result = workspace.runBuckCommand("clean", "--keep-cache");
    result.assertSuccess();

    // Now the cache is clean, do the build where we expect the results to come from the cache
    result = workspace.runBuckBuild("//:android-lib");
    result.assertSuccess();
  }

  @Test
  public void rootDirectoryDoesntChangeBuild() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cached_build", tmp);
    workspace.setUp();
    Path outputOne = workspace.buildAndReturnOutput("//:AService");

    ProjectWorkspace workspaceTwo =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cached_build", tmp2);
    workspaceTwo.setUp();
    Path outputTwo = workspaceTwo.buildAndReturnOutput("//:AService");

    assertEquals(
        workspace.getBuildLog().getRuleKey("//:AService"),
        workspaceTwo.getBuildLog().getRuleKey("//:AService"));

    try (ZipFile zipOne = new ZipFile(outputOne.toFile());
        ZipFile zipTwo = new ZipFile(outputTwo.toFile())) {
      Enumeration<? extends ZipEntry> entriesOne = zipOne.entries(), entriesTwo = zipTwo.entries();

      while (entriesOne.hasMoreElements()) {
        assertTrue(entriesTwo.hasMoreElements());
        ZipEntry entryOne = entriesOne.nextElement(), entryTwo = entriesTwo.nextElement();
        // Compare data first, otherwise crc difference will cause a failure and you don't get to
        // see the actual difference.
        assertEquals(zipEntryData(zipOne, entryOne), zipEntryData(zipTwo, entryTwo));
        assertEquals(zipEntryDebugString(entryOne), zipEntryDebugString(entryTwo));
      }
      assertFalse(entriesTwo.hasMoreElements());
    }
    assertEquals(
        new String(Files.readAllBytes(outputOne)), new String(Files.readAllBytes(outputTwo)));
  }

  private String zipEntryDebugString(ZipEntry entryOne) {
    return "<ZE name="
        + entryOne.getName()
        + " crc="
        + entryOne.getCrc()
        + " comment="
        + entryOne.getComment()
        + " size="
        + entryOne.getSize()
        + " atime="
        + entryOne.getLastAccessTime()
        + " mtime="
        + entryOne.getLastModifiedTime()
        + " ctime="
        + entryOne.getCreationTime()
        + ">";
  }

  private String zipEntryData(ZipFile zip, ZipEntry entry) throws IOException {
    return CharStreams.toString(new InputStreamReader(zip.getInputStream(entry)));
  }
}
