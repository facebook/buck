/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.httpserver;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URL;
import javax.annotation.Nullable;
import org.eclipse.jetty.server.Request;

public interface TemplateHandlerDelegate {

  /** @return templates that are available as resources relative to the implementation's class. */
  URL getTemplateGroup();

  /**
   * @param baseRequest Request that will be served by this handler. This gives the handler the
   *     opportunity to return a different template based on the data in the request.
   */
  String getTemplateForRequest(Request baseRequest);

  /**
   * @param baseRequest Request that will be served by this handler.
   * @return Map to populate the template returned by {@link #getTemplateForRequest(Request)}. If
   *     the request is malformed, then return {@code null} in this method to indicate an error.
   */
  @Nullable
  ImmutableMap<String, Object> getDataForRequest(Request baseRequest) throws IOException;
}
