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

package com.facebook.buck.httpserver;

import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.httpserver.TracesHelper.TraceAttributes;
import com.facebook.buck.testutil.FakeInputStreams;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.io.InputSupplier;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;

public class TracesHelperTest extends EasyMockSupport {

  @Test
  @SuppressWarnings({ "rawtypes", "unchecked" })
  public void testGetTraceAttributesForId() {
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);

    File traceFile = createMock(File.class);
    expect(traceFile.lastModified()).andStubReturn(1000L);
    String name = "build.a.trace";
    expect(traceFile.getName()).andStubReturn(name);
    Path pathToTraceFile = BuckConstant.BUCK_TRACE_DIR.resolve(name);
    expect(projectFilesystem.getFileForRelativePath(pathToTraceFile)).andStubReturn(traceFile);
    String buckBuildJson =
        "[" +
          "{" +
            "\"cat\":\"buck\"," +
            "\"name\":\"build\"," +
            "\"ph\":\"B\"," +
            "\"pid\":0," +
            "\"tid\":1," +
            "\"ts\":5621911884918," +
            "\"args\":{\"command_args\":\"buck\"}" +
          "}" +
        "]";
    expect(projectFilesystem.getInputSupplierForRelativePath(
        pathToTraceFile))
        .andReturn((InputSupplier) FakeInputStreams.createInputSupplierFromString(buckBuildJson));

    replayAll();

    TracesHelper helper = new TracesHelper(projectFilesystem);
    TraceAttributes traceAttributes = helper.getTraceAttributesFor("a");
    assertEquals(
        "TracesHelper should be able to extract the command.",
        Optional.of("buck build buck"),
        traceAttributes.getCommand());
    assertEquals(1000L, traceAttributes.getLastModifiedTime());

    // We cannot verify the contents of getFormattedDateTime() because they may vary depending on
    // timezone and locale.
    assertNotNull(Strings.emptyToNull(traceAttributes.getFormattedDateTime()));

    verifyAll();
  }

  @Test
  @SuppressWarnings({ "rawtypes", "unchecked" })
  public void testGetTraceAttributesForJsonWithoutName() {
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);

    File traceFile = createMock(File.class);
    expect(traceFile.lastModified()).andStubReturn(2000L);
    String name = "build.b.trace";
    expect(traceFile.getName()).andStubReturn(name);
    Path pathToTraceFile = BuckConstant.BUCK_TRACE_DIR.resolve(name);
    expect(projectFilesystem.getFileForRelativePath(pathToTraceFile)).andStubReturn(traceFile);
    String buckBuildJson =
        "[" +
          "{" +
            "\"cat\":\"buck\"," +
            "\"ph\":\"B\"," +
            "\"pid\":0," +
            "\"tid\":1," +
            "\"ts\":5621911884918," +
            "\"args\":{\"command_args\":\"buck\"}" +
          "}" +
        "]";
    expect(projectFilesystem.getInputSupplierForRelativePath(
        pathToTraceFile))
        .andReturn((InputSupplier) FakeInputStreams.createInputSupplierFromString(buckBuildJson));

    replayAll();

    TracesHelper helper = new TracesHelper(projectFilesystem);
    TraceAttributes traceAttributes = helper.getTraceAttributesFor("b");
    assertEquals(
        "TracesHelper should not be able to extract the command because there is no name " +
            "attribute.",
        Optional.absent(),
        traceAttributes.getCommand());
    assertEquals(2000L, traceAttributes.getLastModifiedTime());

    verifyAll();
  }

  @Test
  @SuppressWarnings({ "rawtypes", "unchecked" })
  public void testGetTraceAttributesForJsonWithoutCommandArgs() {
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);

    File traceFile = createMock(File.class);
    expect(traceFile.lastModified()).andStubReturn(2000L);
    String name = "build.c.trace";
    expect(traceFile.getName()).andStubReturn(name);
    Path pathToTraceFile = BuckConstant.BUCK_TRACE_DIR.resolve(name);
    expect(projectFilesystem.getFileForRelativePath(pathToTraceFile)).andStubReturn(traceFile);
    String buckBuildJson =
        "[" +
          "{" +
            "\"cat\":\"buck\"," +
            "\"ph\":\"B\"," +
            "\"pid\":0," +
            "\"tid\":1," +
            "\"ts\":5621911884918" +
          "}" +
        "]";
    expect(projectFilesystem.getInputSupplierForRelativePath(
        pathToTraceFile))
        .andReturn((InputSupplier) FakeInputStreams.createInputSupplierFromString(buckBuildJson));

    replayAll();

    TracesHelper helper = new TracesHelper(projectFilesystem);
    TraceAttributes traceAttributes = helper.getTraceAttributesFor("c");
    assertEquals(
        "TracesHelper should not be able to extract the command because there is no " +
            "command_args attribute.",
        Optional.absent(),
        traceAttributes.getCommand());
    assertEquals(2000L, traceAttributes.getLastModifiedTime());

    verifyAll();
  }
}
