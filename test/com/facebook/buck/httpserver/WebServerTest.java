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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.junit.Test;

public class WebServerTest {

  @Test
  public void testCreateHandlersCoversExpectedContextPaths() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    WebServer webServer = new WebServer(/* port */ 9999, projectFilesystem);
    ImmutableList<ContextHandler> handlers = webServer.createHandlers();
    Map<String, ContextHandler> contextPathToHandler = new HashMap<>();
    for (ContextHandler handler : handlers) {
      contextPathToHandler.put(handler.getContextPath(), handler);
    }

    Function<String, TemplateHandlerDelegate> getDelegate =
        contextPath ->
            ((TemplateHandler) contextPathToHandler.get(contextPath).getHandler()).getDelegate();
    assertTrue(getDelegate.apply("/") instanceof IndexHandlerDelegate);
    assertTrue(contextPathToHandler.get("/static").getHandler() instanceof StaticResourcesHandler);
    assertTrue(getDelegate.apply("/trace") instanceof TraceHandlerDelegate);
    assertTrue(getDelegate.apply("/traces") instanceof TracesHandlerDelegate);
    assertTrue(contextPathToHandler.get("/tracedata").getHandler() instanceof TraceDataHandler);
  }
}
