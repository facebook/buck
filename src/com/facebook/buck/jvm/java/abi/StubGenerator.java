/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.jvm.java.JavacEventSink;
import com.facebook.buck.jvm.java.JavacEventSinkScopedSimplePerfEvent;
import com.facebook.buck.jvm.java.lang.model.ElementsExtended;
import com.facebook.buck.util.zip.JarBuilder;
import java.io.IOException;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.util.Types;

public class StubGenerator {
  private final SourceVersion version;
  private final ElementsExtended elements;
  private final Types types;
  private final Messager messager;
  private final JarBuilder jarBuilder;
  private final JavacEventSink eventSink;
  private final AbiGenerationMode abiCompatibilityMode;
  private final boolean includeParameterMetadata;

  public StubGenerator(
      SourceVersion version,
      ElementsExtended elements,
      Types types,
      Messager messager,
      JarBuilder jarBuilder,
      JavacEventSink eventSink,
      AbiGenerationMode abiCompatibilityMode,
      boolean includeParameterMetadata) {
    this.version = version;
    this.elements = elements;
    this.types = types;
    this.messager = messager;
    this.jarBuilder = jarBuilder;
    this.eventSink = eventSink;
    this.abiCompatibilityMode = abiCompatibilityMode;
    this.includeParameterMetadata = includeParameterMetadata;
  }

  public void generate(Set<Element> topLevelElements) {
    try (JavacEventSinkScopedSimplePerfEvent ignored =
        new JavacEventSinkScopedSimplePerfEvent(eventSink, "generate_stubs")) {
      new StubJar(version, elements, types, messager, topLevelElements, includeParameterMetadata)
          .setCompatibilityMode(abiCompatibilityMode)
          .writeTo(jarBuilder);
    } catch (IOException e) {
      throw new HumanReadableException("Failed to generate abi: %s", e.getMessage());
    }
  }
}
