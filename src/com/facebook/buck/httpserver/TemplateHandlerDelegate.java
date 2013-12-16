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

import com.google.common.collect.ImmutableSet;
import com.google.template.soy.data.SoyMapData;

import org.eclipse.jetty.server.Request;

import java.io.IOException;

import javax.annotation.Nullable;

public interface TemplateHandlerDelegate {

  /**
   * @return Set of templates that are available as resources relative to the implementation's
   *     class.
   */
  public ImmutableSet<String> getTemplates();

  /**
   * @param baseRequest Request that will be served by this handler. This gives the handler the
   *     opportunity to return a different template based on the data in the request.
   */
  public String getTemplateForRequest(Request baseRequest);

  /**
   * @param baseRequest Request that will be served by this handler.
   * @return A {@link SoyMapData} to populate the template returned by
   *     {@link #getTemplateForRequest(Request)}. If the request is malformed, then return
   *     {@code null} in this method to indicate an error.
   */
  @Nullable
  public SoyMapData getDataForRequest(Request baseRequest) throws IOException;
}
