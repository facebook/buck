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

import com.google.common.net.MediaType;

import org.easymock.EasyMockSupport;
import org.eclipse.jetty.server.Request;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletResponse;

public class ResponsesTest extends EasyMockSupport {

  @Test
  public void testWriteSuccessfulResponse() throws IOException {
    String content = "<html>Hello, world!</html>";
    Request baseRequest = createMock(Request.class);
    baseRequest.setHandled(true);

    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.setStatus(200);
    response.setContentType("text/html; charset=utf-8");
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    expect(response.getWriter()).andReturn(printWriter);
    response.flushBuffer();

    replayAll();
    Responses.writeSuccessfulResponse(content, MediaType.HTML_UTF_8, baseRequest, response);
    verifyAll();

    assertEquals(content, stringWriter.toString());
  }

  @Test
  public void testWriteFailedResponse() throws IOException {
    Request baseRequest = createMock(Request.class);
    baseRequest.setHandled(true);

    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.setStatus(500);
    response.setContentType("text/plain; charset=utf-8");
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    expect(response.getWriter()).andReturn(printWriter);
    response.flushBuffer();

    replayAll();
    Responses.writeFailedResponse(baseRequest, response);
    verifyAll();

    assertEquals("ERROR", stringWriter.toString());
  }
}
