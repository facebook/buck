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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.collect.Maps;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class WebServerTest {

  @Test
  public void testCreateHandlersCoversExpectedContextPaths() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    WebServer webServer = new WebServer(/* port */ 9999, projectFilesystem, "/static/");
    List<ContextHandler> handlers = webServer.createHandlers();
    final Map<String, ContextHandler> contextPathToHandler = Maps.newHashMap();
    for (ContextHandler handler : handlers) {
      contextPathToHandler.put(handler.getContextPath(), handler);
    }

    Function<String, TemplateHandlerDelegate> getDelegate =
        new Function<String, TemplateHandlerDelegate>() {
          @Override
          public TemplateHandlerDelegate apply(String contextPath) {
            return ((TemplateHandler) contextPathToHandler.get(contextPath).getHandler())
                .getDelegate();
          }
        };
    assertTrue(getDelegate.apply("/") instanceof IndexHandlerDelegate);
    assertTrue(contextPathToHandler.get("/static").getHandler() instanceof ResourceHandler);
    assertTrue(getDelegate.apply("/trace") instanceof TraceHandlerDelegate);
    assertTrue(getDelegate.apply("/traces") instanceof TracesHandlerDelegate);
    assertTrue(contextPathToHandler.get("/tracedata").getHandler() instanceof TraceDataHandler);
  }
}
