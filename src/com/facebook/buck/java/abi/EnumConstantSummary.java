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

package com.facebook.buck.java.abi;

import javax.lang.model.element.VariableElement;

class EnumConstantSummary implements Renderable {

  private final String summary;

  public EnumConstantSummary(VariableElement element) {
    summary = element.getSimpleName().toString() + "\n";
  }

  @Override
  public void appendTo(StringBuilder builder) {
    builder.append(summary);
  }
}
