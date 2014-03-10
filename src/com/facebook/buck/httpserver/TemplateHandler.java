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
import com.google.common.base.Preconditions;
import com.google.common.net.MediaType;
import com.google.template.soy.SoyFileSet;
import com.google.template.soy.data.SoyMapData;
import com.google.template.soy.tofu.SoyTofu;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.IOException;
import java.net.URL;

import javax.annotation.Nullable;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@link Handler} that provides a response by populating a Soy template.
 */
class TemplateHandler extends AbstractHandler {

  /**
   * Whether to re-parse the templates for this handler on every request.
   * <p>
   * Should be set to {@code false} when checked in, but it is convenient to set this to
   * {@code true} in development.
   */
  private static final boolean DEBUG = Boolean.getBoolean("buck.soy.debug");

  private final TemplateHandlerDelegate delegate;

  /**
   * This field is set lazily by {@link #createAndMaybeCacheSoyTofu()}.
   */
  @Nullable
  private volatile SoyTofu tofu;

  /**
   * @param soyFiles The set of templates that can be used to produce a response from this handler.
   */
  protected TemplateHandler(TemplateHandlerDelegate templateBasedHandler) {
    this.delegate = Preconditions.checkNotNull(templateBasedHandler);
  }

  /**
   * Handles a request. Invokes {@link TemplateHandlerDelegate#getTemplateForRequest(Request)} to
   * get the template and {@link TemplateHandlerDelegate#getDataForRequest(Request)} to get the
   * template data, and then combines them to produce the response.
   */
  @Override
  public void handle(String target,
      Request baseRequest,
      HttpServletRequest request,
      HttpServletResponse response)
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
    SoyTofu.Renderer renderer = createAndMaybeCacheSoyTofu().newRenderer(template);

    SoyMapData data = delegate.getDataForRequest(baseRequest);
    if (data != null) {
      renderer.setData(data);
      return renderer.render();
    } else {
      return null;
    }
  }

  @VisibleForTesting
  TemplateHandlerDelegate getDelegate() {
    return delegate;
  }

  /**
   * Returns the {@link SoyTofu} for the {@link #soyFiles}. If {@link #DEBUG} is {@code false}, then
   * the result will be cached because {@link SoyTofu} objects can be expensive to construct.
   */
  private SoyTofu createAndMaybeCacheSoyTofu() {
    // In debug mode, create a new SoyTofu object for each request. This makes it possible to test
    // new versions of the templates without restarting buckd.
    if (DEBUG) {
      return createSoyTofu();
    }

    // In production, cache the SoyTofu object for efficiency.
    if (tofu != null) {
      return tofu;
    }

    synchronized (this) {
      if (tofu == null) {
        tofu = createSoyTofu();
      }
    }
    return tofu;
  }

  /** Creates the {@link SoyTofu} for the {@link #soyFiles}. */
  private SoyTofu createSoyTofu() {
    SoyFileSet.Builder builder = new SoyFileSet.Builder();
    for (String soyFile : delegate.getTemplates()) {
      URL url = delegate.getClass().getResource(soyFile);
      builder.add(url);
    }
    return builder.build().compileToTofu();
  }
}
