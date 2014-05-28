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

package com.facebook.buck.rules;

import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

public class OnDiskBuildInfoTest extends EasyMockSupport {

  @Test
  public void testGetOutputFileContentsByLine() throws IOException {
    String pathToOutputFile = "buck-out/gen/java/com/example/classes.txt";

    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    List<String> lines = ImmutableList.of(
        "com/example/Bar.class 087b7707a5f8e0a2adf5652e3cd2072d89a197dc",
        "com/example/Foo.class e4fccb7520b7795e632651323c63217c9f59f72a");
    expect(projectFilesystem.readLines(Paths.get(pathToOutputFile))).andReturn(lines);

    Buildable buildable = new FakeBuildable(new BuildTarget("//test", "test"))
        .setPathToOutputFile(pathToOutputFile);

    replayAll();

    OnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        new BuildTarget("//java/com/example", "ex"),
        projectFilesystem);
    List<String> observedLines = onDiskBuildInfo.getOutputFileContentsByLine(buildable);
    assertEquals(lines, observedLines);

    verifyAll();
  }

  @Test(expected = NullPointerException.class)
  public void testGetOutputFileContentsByLineRejectsNullBuildable() throws IOException {
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    OnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        new BuildTarget("//java/com/example", "ex"),
        projectFilesystem);

    replayAll();

    onDiskBuildInfo.getOutputFileContentsByLine((Buildable) null);
  }

  @Test(expected = NullPointerException.class)
  public void testGetOutputFileContentsByLineRejectsNullOutputPath() throws IOException {
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    OnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        new BuildTarget("//java/com/example", "ex"),
        projectFilesystem);

    Buildable buildable = new FakeBuildable(new BuildTarget("//test", "test"));
    onDiskBuildInfo.getOutputFileContentsByLine(buildable);
  }

}
