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

import com.google.common.net.MediaType;

import org.eclipse.jetty.server.Request;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

/**
 * Utility methods for writing HTTP responses.
 */
class Responses {

  /** Utility class: do not instantiate. */
  private Responses() {}

  /**
   * Writes the specified content to the response with a status code of 200.
   */
  static void writeSuccessfulResponse(String content,
      MediaType mediaType,
      Request baseRequest,
      HttpServletResponse response)
      throws IOException {
    writeResponse(content, mediaType, baseRequest, response, HttpServletResponse.SC_OK);
  }

  /** Responds with a 500. */
  static void writeFailedResponse(Request baseRequest, HttpServletResponse response)
      throws IOException {
    writeResponse("ERROR",
        MediaType.PLAIN_TEXT_UTF_8,
        baseRequest,
        response,
        /* status */ 500);
  }

  /**
   * Writes the specified content to the response with the specified status code.
   */
  private static void writeResponse(String content,
      MediaType mediaType,
      Request baseRequest,
      HttpServletResponse response,
      int status)
      throws IOException {
    response.setContentType(mediaType.toString());
    response.setStatus(status);

    response.getWriter().write(content);

    response.flushBuffer();
    baseRequest.setHandled(true);
  }
}
