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

package com.facebook.buck.java;

import com.facebook.buck.rules.AnnotationProcessingData;

/**
 * Utilities for dealing with {@link AnnotationProcessingDataDecorator}s.
 */
public class AnnotationProcessingDataDecorators {

  /** Utility class: do not instantiate. */
  private AnnotationProcessingDataDecorators() {}

  private static final AnnotationProcessingDataDecorator IDENTITY =
      new AnnotationProcessingDataDecorator() {
    @Override
    public AnnotationProcessingData decorate(AnnotationProcessingData annotationProcessingData) {
      return annotationProcessingData;
    }
  };

  /**
   * @return a decorator that returns the value that is passed to it.
   */
  public static AnnotationProcessingDataDecorator identity() {
    return IDENTITY;
  }

}
