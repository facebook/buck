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

import com.google.common.collect.ImmutableSet;

abstract class AbstractTemplateHandlerDelegate implements TemplateHandlerDelegate {

  private static final ImmutableSet<String> COMMON_TEMPLATES = ImmutableSet.of("common.soy");

  private final ImmutableSet<String> templates;

  /**
   * @param templates The set of templates that can be used to produce a response from this handler.
   */
  AbstractTemplateHandlerDelegate(ImmutableSet<String> templates) {
    this.templates = ImmutableSet.<String>builder()
        .addAll(COMMON_TEMPLATES)
        .addAll(templates)
        .build();
  }

  @Override
  public ImmutableSet<String> getTemplates() {
    return templates;
  }
}
