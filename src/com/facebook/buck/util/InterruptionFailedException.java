/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

/**
 * Signals that processing could not be interrupted.
 * This exception is meant only to be caught at the top level of the application.
 */
@SuppressWarnings("serial")
public class InterruptionFailedException extends RuntimeException {

  public InterruptionFailedException(String message) {
    super(message);
  }
}
