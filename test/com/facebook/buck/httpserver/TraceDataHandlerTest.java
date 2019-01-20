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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.trace.BuildTraces;
import com.google.common.base.Charsets;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter; // NOPMD required by API
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.easymock.EasyMockSupport;
import org.eclipse.jetty.server.Request;
import org.junit.Test;

// TODO(simons): Use a FakeProjectFilesystem throughout.
public class TraceDataHandlerTest extends EasyMockSupport {

  @Test
  public void testIdPattern() {
    Matcher matcher = TraceDataHandler.ID_PATTERN.matcher("/4hSQpLBb");
    assertTrue(matcher.matches());
    assertEquals("4hSQpLBb", matcher.group(1));
  }

  @Test
  public void testCallbackPattern() {
    assertTrue(TraceDataHandler.CALLBACK_PATTERN.matcher("callback").matches());
    assertTrue(TraceDataHandler.CALLBACK_PATTERN.matcher("my.callback").matches());
    assertFalse(TraceDataHandler.CALLBACK_PATTERN.matcher("(createCallback())").matches());
  }

  @Test
  public void testHandleGet() throws IOException {
    Request baseRequest = createMock(Request.class);
    expect(baseRequest.getMethod()).andReturn("GET");
    expect(baseRequest.getPathInfo()).andReturn("/abcdef");
    expect(baseRequest.getParameter("callback")).andReturn(null);
    baseRequest.setHandled(true);
    HttpServletRequest request = createMock(HttpServletRequest.class);

    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.setStatus(200);
    response.setContentType("application/javascript; charset=utf-8");
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter); // NOPMD required by API
    expect(response.getWriter()).andReturn(printWriter);
    response.flushBuffer();

    BuildTraces buildTraces = createMock(BuildTraces.class);
    Iterable<InputStream> traces =
        Collections.singletonList(
            new ByteArrayInputStream("{\"foo\":\"bar\"}".getBytes(Charsets.UTF_8)));
    expect(buildTraces.getInputsForTraces("abcdef")).andReturn(traces);
    TraceDataHandler traceDataHandler = new TraceDataHandler(buildTraces);

    replayAll();
    traceDataHandler.handle("/trace/abcdef", baseRequest, request, response);
    verifyAll();

    assertEquals("[{\"foo\":\"bar\"}]", stringWriter.toString());
  }

  @Test
  public void testHandleGetWithMultipleTrace() throws IOException {
    Request baseRequest = createMock(Request.class);
    expect(baseRequest.getMethod()).andReturn("GET");
    expect(baseRequest.getPathInfo()).andReturn("/abcdef");
    expect(baseRequest.getParameter("callback")).andReturn(null);
    baseRequest.setHandled(true);
    HttpServletRequest request = createMock(HttpServletRequest.class);

    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.setStatus(200);
    response.setContentType("application/javascript; charset=utf-8");
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter); // NOPMD required by API
    expect(response.getWriter()).andReturn(printWriter);
    response.flushBuffer();

    BuildTraces buildTraces = createMock(BuildTraces.class);
    Iterable<InputStream> traces =
        Arrays.asList(
            new ByteArrayInputStream("{\"foo\":\"bar\"}".getBytes(Charsets.UTF_8)),
            new ByteArrayInputStream("{\"baz\":\"blech\"}".getBytes(Charsets.UTF_8)));
    expect(buildTraces.getInputsForTraces("abcdef")).andReturn(traces);

    TraceDataHandler traceDataHandler = new TraceDataHandler(buildTraces);

    replayAll();
    traceDataHandler.handle("/trace/abcdef", baseRequest, request, response);
    verifyAll();

    assertEquals("[{\"foo\":\"bar\"},{\"baz\":\"blech\"}]", stringWriter.toString());
  }

  @Test
  public void testHandleGetWithCallback() throws IOException {
    Request baseRequest = createMock(Request.class);
    expect(baseRequest.getMethod()).andReturn("GET");
    expect(baseRequest.getPathInfo()).andReturn("/abcdef");
    expect(baseRequest.getParameter("callback")).andReturn("my.callback");
    baseRequest.setHandled(true);
    HttpServletRequest request = createMock(HttpServletRequest.class);

    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.setStatus(200);
    response.setContentType("application/javascript; charset=utf-8");
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter); // NOPMD required by API
    expect(response.getWriter()).andReturn(printWriter);
    response.flushBuffer();

    BuildTraces buildTraces = createMock(BuildTraces.class);
    Iterable<InputStream> traces =
        Collections.singletonList(
            new ByteArrayInputStream("{\"foo\":\"bar\"}".getBytes(Charsets.UTF_8)));
    expect(buildTraces.getInputsForTraces("abcdef")).andReturn(traces);
    TraceDataHandler traceDataHandler = new TraceDataHandler(buildTraces);

    replayAll();
    traceDataHandler.handle("/trace/abcdef?callback=my.callback", baseRequest, request, response);
    verifyAll();

    assertEquals("my.callback([{\"foo\":\"bar\"}]);\n", stringWriter.toString());
  }

  @Test
  public void testHandleGetWithMultipleTraceCallback() throws IOException {
    Request baseRequest = createMock(Request.class);
    expect(baseRequest.getMethod()).andReturn("GET");
    expect(baseRequest.getPathInfo()).andReturn("/abcdef");
    expect(baseRequest.getParameter("callback")).andReturn("my.callback");
    baseRequest.setHandled(true);
    HttpServletRequest request = createMock(HttpServletRequest.class);

    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.setStatus(200);
    response.setContentType("application/javascript; charset=utf-8");
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter); // NOPMD required by API
    expect(response.getWriter()).andReturn(printWriter);
    response.flushBuffer();

    BuildTraces buildTraces = createMock(BuildTraces.class);
    Iterable<InputStream> traces =
        Arrays.asList(
            new ByteArrayInputStream("{\"foo\":\"bar\"}".getBytes(Charsets.UTF_8)),
            new ByteArrayInputStream("{\"baz\":\"blech\"}".getBytes(Charsets.UTF_8)));
    expect(buildTraces.getInputsForTraces("abcdef")).andReturn(traces);

    TraceDataHandler traceDataHandler = new TraceDataHandler(buildTraces);

    replayAll();
    traceDataHandler.handle("/trace/abcdef?callback=my.callback", baseRequest, request, response);
    verifyAll();

    assertEquals(
        "my.callback([{\"foo\":\"bar\"},{\"baz\":\"blech\"}]);\n", stringWriter.toString());
  }
}
