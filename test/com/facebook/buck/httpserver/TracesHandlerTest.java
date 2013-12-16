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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;

import org.easymock.EasyMockSupport;
import org.eclipse.jetty.server.Request;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class TracesHandlerTest extends EasyMockSupport {

  @Test
  public void testHandleGet() throws IOException {
    File a = createMock(File.class);
    expect(a.lastModified()).andStubReturn(1000L);
    expect(a.getName()).andStubReturn("build.a.trace");

    File b = createMock(File.class);
    expect(b.lastModified()).andStubReturn(4000L);
    expect(b.getName()).andStubReturn("build.b.trace");

    File c = createMock(File.class);
    expect(c.lastModified()).andStubReturn(2000L);
    expect(c.getName()).andStubReturn("build.c.trace");

    File d = createMock(File.class);
    expect(d.lastModified()).andStubReturn(3000L);
    expect(d.getName()).andStubReturn("build.d.trace");

    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem.listFiles(BuckConstant.BUCK_TRACE_DIR)).andReturn(
        new File[] {a, b, c, d});

    Request baseRequest = createMock(Request.class);

    replayAll();

    TracesHandlerDelegate delegate = new TracesHandlerDelegate(projectFilesystem);
    TemplateHandler tracesHandler = new TemplateHandler(delegate);
    String html = tracesHandler.createHtmlForResponse(baseRequest);

    int indexB = html.indexOf("<a href=\"/trace/b\" target=\"_blank\">build.b.trace</a>");
    assertTrue(indexB > 0);

    int indexD = html.indexOf("<a href=\"/trace/d\" target=\"_blank\">build.d.trace</a>");
    assertTrue(indexD > indexB);

    int indexC = html.indexOf("<a href=\"/trace/c\" target=\"_blank\">build.c.trace</a>");
    assertTrue(indexC > indexD);

    int indexA = html.indexOf("<a href=\"/trace/a\" target=\"_blank\">build.a.trace</a>");
    assertTrue(indexA > indexC);

    verifyAll();
  }
}
