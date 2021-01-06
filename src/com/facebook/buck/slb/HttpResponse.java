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

package com.facebook.buck.slb;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

public interface HttpResponse extends Closeable {
  /** @return HTTP Response code. */
  int statusCode();

  /** @return HTTP Response code message. */
  String statusMessage();

  /**
   * @return Number of bytes in the body of this response.
   * @throws IOException
   */
  long contentLength() throws IOException;

  /**
   * @return Stream with the response body.
   * @throws IOException
   */
  InputStream getBody();

  /** @return The full URL of the request that generated this response. */
  String requestUrl();
}
