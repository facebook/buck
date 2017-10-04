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

import com.facebook.buck.jvm.java.JavacEventSink;
import com.facebook.buck.jvm.java.JavacEventSinkScopedSimplePerfEvent;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.zip.JarBuilder;
import java.io.IOException;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;

public class StubGenerator {
  private final SourceVersion version;
  private final Elements elements;
  private final Messager messager;
  private final JarBuilder jarBuilder;
  private final JavacEventSink eventSink;

  public StubGenerator(
      SourceVersion version,
      Elements elements,
      Messager messager,
      JarBuilder jarBuilder,
      JavacEventSink eventSink) {
    this.version = version;
    this.elements = elements;
    this.messager = messager;
    this.jarBuilder = jarBuilder;
    this.eventSink = eventSink;
  }

  public void generate(Set<Element> topLevelElements) {
    try (JavacEventSinkScopedSimplePerfEvent ignored =
        new JavacEventSinkScopedSimplePerfEvent(eventSink, "generate_stubs")) {
      new StubJar(version, elements, messager, topLevelElements).writeTo(jarBuilder);
    } catch (IOException e) {
      throw new HumanReadableException("Failed to generate abi: %s", e.getMessage());
    }
  }
}
