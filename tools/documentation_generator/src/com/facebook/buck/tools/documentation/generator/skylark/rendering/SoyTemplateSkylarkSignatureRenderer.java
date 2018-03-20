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

package com.facebook.buck.tools.documentation.generator.skylark.rendering;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.stringtemplate.v4.ST;

/** Renders a soy template suitable for usage with the rest of buckbuild website documents. */
class SoyTemplateSkylarkSignatureRenderer {

  private static final char DELIMITER_START_CHAR = '%';
  private static final char DELIMITER_STOP_CHAR = '%';
  private static final String TEMPLATE_NAME = "signature_template.stg";

  private final Supplier<String> stringTemplateSupplier;

  SoyTemplateSkylarkSignatureRenderer() {
    this.stringTemplateSupplier =
        Suppliers.memoize(
            () -> {
              try {
                return loadTemplate();
              } catch (IOException e) {
                throw new UncheckedExecutionException(e);
              }
            });
  }

  /**
   * Renders provided Skylark signature into a soy template content similar to manually written
   * templates for all Python DSL functions.
   */
  String render(SkylarkSignature skylarkSignature) {
    ST stringTemplate =
        new ST(stringTemplateSupplier.get(), DELIMITER_START_CHAR, DELIMITER_STOP_CHAR);
    // open and close brace characters are not allowed inside of StringTemplate loops and using
    // named parameters seems nicer than their unicode identifiers
    stringTemplate.add("openCurly", "{");
    stringTemplate.add("closeCurly", "}");
    stringTemplate.add("signature", toMap(skylarkSignature));
    return stringTemplate.render();
  }

  private String loadTemplate() throws IOException {
    URL template = Resources.getResource(SoyTemplateSkylarkSignatureRenderer.class, TEMPLATE_NAME);
    return Resources.toString(template, StandardCharsets.UTF_8);
  }

  private static ImmutableMap<String, Object> toMap(SkylarkSignature skylarkSignature) {
    return ImmutableMap.of(
        "name", skylarkSignature.name(),
        "doc", skylarkSignature.doc(),
        "parameters",
            Arrays.stream(skylarkSignature.parameters())
                .map(SoyTemplateSkylarkSignatureRenderer::toMap)
                .collect(Collectors.toList()));
  }

  private static ImmutableMap<String, String> toMap(Param param) {
    return ImmutableMap.of(
        "name", param.name(),
        "doc", param.doc(),
        "defaultValue", param.defaultValue());
  }
}
