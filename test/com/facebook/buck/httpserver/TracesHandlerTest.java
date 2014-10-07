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

import com.facebook.buck.httpserver.TracesHelper.TraceAttributes;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMockSupport;
import org.eclipse.jetty.server.Request;
import org.junit.Test;

import java.io.IOException;

public class TracesHandlerTest extends EasyMockSupport {

  @Test
  public void testHandleGet() throws IOException {
    TracesHelper tracesHelper = createMock(TracesHelper.class);
    expect(tracesHelper.getTraceAttributesFor(BuckConstant.BUCK_TRACE_DIR.resolve("build.a.trace")))
        .andReturn(new TraceAttributes(Optional.of("buck build buck"), 1000L));
    expect(tracesHelper.getTraceAttributesFor(BuckConstant.BUCK_TRACE_DIR.resolve("build.b.trace")))
        .andReturn(new TraceAttributes(Optional.of("buck test --all --code-coverage"), 4000L));
    expect(tracesHelper.getTraceAttributesFor(BuckConstant.BUCK_TRACE_DIR.resolve("build.c.trace")))
        .andReturn(new TraceAttributes(Optional.<String>absent(), 2000L));
    expect(tracesHelper.getTraceAttributesFor(BuckConstant.BUCK_TRACE_DIR.resolve("build.d.trace")))
        .andReturn(
            new TraceAttributes(Optional.of("buck test //test/com/facebook/buck/cli:cli"), 3000L));

    expect(tracesHelper.listTraceFilesByLastModified()).andReturn(
        ImmutableList.of(
            BuckConstant.BUCK_TRACE_DIR.resolve("build.b.trace"),
            BuckConstant.BUCK_TRACE_DIR.resolve("build.d.trace"),
            BuckConstant.BUCK_TRACE_DIR.resolve("build.c.trace"),
            BuckConstant.BUCK_TRACE_DIR.resolve("build.a.trace")
        ));
    Request baseRequest = createMock(Request.class);

    replayAll();

    TracesHandlerDelegate delegate = new TracesHandlerDelegate(tracesHelper);
    TemplateHandler tracesHandler = new TemplateHandler(delegate);
    String html = tracesHandler.createHtmlForResponse(baseRequest);

    int indexB = html.indexOf("<a href=\"/trace/b\"><tt>build.b.trace</tt></a>");
    assertTrue(indexB > 0);
    int indexBCommand = html.indexOf("buck test --all --code-coverage");
    assertTrue(indexBCommand > 0);

    int indexD = html.indexOf("<a href=\"/trace/d\"><tt>build.d.trace</tt></a>");
    assertTrue(indexD > indexB);
    int indexDCommand = html.indexOf("buck test //test/com/facebook/buck/cli:cli");
    assertTrue(indexDCommand > indexBCommand);

    int indexC = html.indexOf("<a href=\"/trace/c\"><tt>build.c.trace</tt></a>");
    assertTrue(indexC > indexD);

    int indexA = html.indexOf("<a href=\"/trace/a\"><tt>build.a.trace</tt></a>");
    assertTrue(indexA > indexC);
    int indexACommand = html.indexOf("buck build buck");
    assertTrue(indexACommand > indexDCommand);

    verifyAll();
  }
}
