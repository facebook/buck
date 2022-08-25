/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.cd.serialization.kotlin;

import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription;

/**
 * Marshalling between:
 *
 * <ul>
 *   <li>{@link com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.AnnotationProcessingTool} (the
 *       configuration from kotlin_library/android_library), and
 *   <li>{@link com.facebook.buck.cd.model.kotlin.AnnotationProcessingTool} (part of the protocol
 *       buffer model).
 * </ul>
 */
public class AnnotationProcessingToolSerializer {

  private AnnotationProcessingToolSerializer() {}

  /** Protocol buffer model to internal buck representation. */
  public static KotlinLibraryDescription.AnnotationProcessingTool deserialize(
      com.facebook.buck.cd.model.kotlin.AnnotationProcessingTool annotationProcessingTool) {
    switch (annotationProcessingTool) {
      case KAPT:
        return KotlinLibraryDescription.AnnotationProcessingTool.KAPT;

      case JAVAC:
        return KotlinLibraryDescription.AnnotationProcessingTool.JAVAC;

      case UNRECOGNIZED:
      default:
        throw new IllegalArgumentException(
            "Unrecognised annotation processing tool: " + annotationProcessingTool);
    }
  }
}
