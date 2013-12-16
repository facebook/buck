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

import com.google.common.collect.ImmutableSet;
import com.google.template.soy.data.SoyMapData;

import org.easymock.EasyMockSupport;
import org.eclipse.jetty.server.Request;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class TemplateHandlerTest extends EasyMockSupport {

  @Test
  public void testHandleSimpleRequest() throws IOException, ServletException {
    TemplateHandlerDelegate delegate = new TemplateHandlerDelegate() {
      @Override
      public ImmutableSet<String> getTemplates() {
        return ImmutableSet.of("example.soy");
      }

      @Override
      public String getTemplateForRequest(Request baseRequest) {
        return "example.hello";
      }

      @Override
      public SoyMapData getDataForRequest(Request baseRequest) throws IOException {
        return new SoyMapData("name", "Michael");
      }
    };

    String target = "target";
    Request baseRequest = createMock(Request.class);
    baseRequest.setHandled(true);

    HttpServletRequest request = createMock(HttpServletRequest.class);

    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.setStatus(200);
    response.setContentType("text/html; charset=utf-8");
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    expect(response.getWriter()).andReturn(printWriter);
    response.flushBuffer();

    replayAll();
    TemplateHandler handler = new TemplateHandler(delegate);
    handler.handle(target, baseRequest, request, response);
    verifyAll();

    assertEquals("Hello, Michael!", stringWriter.toString());
  }

  @Test
  public void testHandleMalformedRequest() throws IOException, ServletException {
    TemplateHandlerDelegate delegate = new TemplateHandlerDelegate() {
      @Override
      public ImmutableSet<String> getTemplates() {
        return ImmutableSet.of("example.soy");
      }

      @Override
      public String getTemplateForRequest(Request baseRequest) {
        return "example.hello";
      }

      @Override
      public SoyMapData getDataForRequest(Request baseRequest) throws IOException {
        // Returning null should cause a 500 to be returned.
        return null;
      }
    };

    String target = "target";
    Request baseRequest = createMock(Request.class);
    baseRequest.setHandled(true);

    HttpServletRequest request = createMock(HttpServletRequest.class);

    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.setStatus(500);
    response.setContentType("text/plain; charset=utf-8");
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    expect(response.getWriter()).andReturn(printWriter);
    response.flushBuffer();

    replayAll();
    TemplateHandler handler = new TemplateHandler(delegate);
    handler.handle(target, baseRequest, request, response);
    verifyAll();

    assertEquals("ERROR", stringWriter.toString());
  }
}
