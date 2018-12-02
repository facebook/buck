/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser.cache;

/**
 * This exception is thrown when there are failures while doing {@link ParserCacheStorage}
 * operations.
 */
public class ParserCacheException extends Exception {
  /**
   * Constructs a {@code ParserCacheException} object
   *
   * @param message the message of the Exception.
   */
  public ParserCacheException(String message) {
    super(message);
  }

  /**
   * Constructs a {@code ParserCacheException} object
   *
   * @param cause the cause for this exception
   * @param message the message of the Exception.
   */
  public ParserCacheException(Throwable cause, String message) {
    super(message, cause);
  }

  /**
   * Constructs a {@code ParserCacheException} object
   *
   * @param format a format {@link String} to create the {@link ParserCacheException} message.
   * @param parameters the parameters for formatting the message.
   */
  public ParserCacheException(String format, Object... parameters) {
    super(String.format(format, parameters));
  }

  /**
   * Constructs a {@code ParserCacheException} object
   *
   * @param cause the cause for this exception
   * @param format a format {@link String} to create the {@link ParserCacheException} message.
   * @param parameters the parameters for formatting the message.
   */
  public ParserCacheException(Throwable cause, String format, Object... parameters) {
    super(String.format(format, parameters), cause);
  }
}
