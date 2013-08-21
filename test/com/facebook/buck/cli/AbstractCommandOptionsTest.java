/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.anyObject;

import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.File;

/** Unit test for {@link AbstractCommandOptions}. */
public class AbstractCommandOptionsTest extends EasyMockSupport {

  private final File pathToNdk = new File("/path/to/ndk");

  @Test
  public void testConstructor() {
    BuckConfig buckConfig = createMock(BuckConfig.class);
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};

    assertSame(buckConfig, options.getBuckConfig());
  }

  private BuckConfig mockNdkBuckConfig(String minNdkVersion, String maxNdkVersion) {
    BuckConfig buckConfig = createMock(BuckConfig.class);

    expect(buckConfig.getMinimumNdkVersion())
        .andReturn(Optional.of(minNdkVersion));
    expect(buckConfig.getMaximumNdkVersion())
        .andReturn(Optional.of(maxNdkVersion));

    return buckConfig;
  }

  private ProjectFilesystem mockNdkFileSystem(String ndkVersion) {
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);

    expect(projectFilesystem.readFirstLineFromFile(anyObject(File.class)))
        .andReturn(Optional.of(ndkVersion));

    return projectFilesystem;
  }

  @Test
  public void testNdkExactVersion() {
    BuckConfig buckConfig = mockNdkBuckConfig("r8", "r8");
    ProjectFilesystem projectFilesystem = mockNdkFileSystem("r8");

    replayAll();
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};
    options.validateNdkVersion(projectFilesystem, pathToNdk);
    verifyAll();
  }

  @Test
  public void testNdkWideVersion() {
    BuckConfig buckConfig = mockNdkBuckConfig("r8", "r8e");
    ProjectFilesystem projectFilesystem = mockNdkFileSystem("r8d");

    replayAll();
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};
    options.validateNdkVersion(projectFilesystem, pathToNdk);
    verifyAll();
  }

  @Test
  public void testNdkTooOldVersion() {
    BuckConfig buckConfig = mockNdkBuckConfig("r8", "r8e");
    ProjectFilesystem projectFilesystem = mockNdkFileSystem("r7");

    replayAll();
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};
    try {
      options.validateNdkVersion(projectFilesystem, pathToNdk);
      fail("Should not be valid");
    } catch (HumanReadableException e) {
      assertEquals("Supported NDK versions are between r8 and r8e but Buck is configured to use r7 from " + pathToNdk.getAbsolutePath(), e.getMessage());
    }
    verifyAll();
  }

  @Test
  public void testNdkTooNewVersion() {
    BuckConfig buckConfig = mockNdkBuckConfig("r8", "r8e");
    ProjectFilesystem projectFilesystem = mockNdkFileSystem("r9");

    replayAll();
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};
    try {
      options.validateNdkVersion(projectFilesystem, pathToNdk);
      fail("Should not be valid");
    } catch (HumanReadableException e) {
      assertEquals("Supported NDK versions are between r8 and r8e but Buck is configured to use r9 from " + pathToNdk.getAbsolutePath(), e.getMessage());
    }
    verifyAll();
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void testNdkOnlyMinVersion() {
    BuckConfig buckConfig = createMock(BuckConfig.class);

    expect(buckConfig.getMinimumNdkVersion())
        .andReturn(Optional.of("r8"));
    Optional<String> empty = Optional.absent();
    expect(buckConfig.getMaximumNdkVersion())
        .andReturn(empty);

    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);

    replayAll();
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};
    try {
      options.validateNdkVersion(projectFilesystem, pathToNdk);
      fail("Should not be valid");
    } catch (HumanReadableException e) {
      assertEquals("Either both min_version and max_version are provided or neither are", e.getMessage());
    }
    verifyAll();
  }
}
