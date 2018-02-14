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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.MediaType;
import java.io.IOException;
import java.util.Map;
import javax.annotation.Nullable;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroupFile;

/** {@link Handler} that provides a response by populating a template. */
class TemplateHandler extends AbstractHandler {

  /**
   * Whether to re-parse the templates for this handler on every request.
   *
   * <p>Should be set to {@code false} when checked in, but it is convenient to set this to {@code
   * true} in development.
   */
  private static final boolean DEBUG = Boolean.getBoolean("buck.soy.debug");

  private final TemplateHandlerDelegate delegate;

  /** This field is set lazily by {@link #createAndMaybeCacheTemplates()}. */
  @Nullable private volatile STGroupFile groupFile;

  protected TemplateHandler(TemplateHandlerDelegate templateBasedHandler) {
    this.delegate = templateBasedHandler;
  }

  /**
   * Handles a request. Invokes {@link TemplateHandlerDelegate#getTemplateForRequest(Request)} to
   * get the template and {@link TemplateHandlerDelegate#getDataForRequest(Request)} to get the
   * template data, and then combines them to produce the response.
   */
  @Override
  public void handle(
      String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {
    String html = createHtmlForResponse(baseRequest);
    if (html != null) {
      Responses.writeSuccessfulResponse(html, MediaType.HTML_UTF_8, baseRequest, response);
    } else {
      Responses.writeFailedResponse(baseRequest, response);
    }
  }

  @Nullable
  String createHtmlForResponse(Request baseRequest) throws IOException {
    String template = delegate.getTemplateForRequest(baseRequest);
    ST st = createAndMaybeCacheTemplates().getInstanceOf(template);

    Map<String, Object> data = delegate.getDataForRequest(baseRequest);
    if (data != null) {
      data.forEach(st::add);
      return st.render();
    } else {
      return null;
    }
  }

  @VisibleForTesting
  TemplateHandlerDelegate getDelegate() {
    return delegate;
  }

  /**
   * Returns the {@link STGroupFile} for {@link TemplateHandlerDelegate#getTemplateGroup()}. If
   * {@link #DEBUG} is {@code false}, then the result will be cached.
   */
  private STGroupFile createAndMaybeCacheTemplates() {
    // In debug mode, create a new STGroupFile for each request. This makes it possible to test
    // new versions of the templates without restarting buckd.
    if (DEBUG) {
      return createSTGroupFile();
    }

    // In production, cache the GroupFile object for efficiency.
    if (groupFile != null) {
      return groupFile;
    }

    synchronized (this) {
      if (groupFile == null) {
        groupFile = createSTGroupFile();
      }
    }
    return groupFile;
  }

  /** Load string templates for {@link TemplateHandlerDelegate#getTemplateGroup()}. */
  private STGroupFile createSTGroupFile() {
    return new STGroupFile(delegate.getTemplateGroup(), "UTF-8", '$', '$');
  }
}
