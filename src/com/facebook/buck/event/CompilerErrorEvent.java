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

package com.facebook.buck.event;

import com.facebook.buck.model.BuildTarget;
import com.google.common.collect.ImmutableSet;

public class CompilerErrorEvent extends AbstractBuckEvent {
  private final BuildTarget target;
  private final String error;
  private final ImmutableSet<String> suggestions;

  public enum CompilerType {
    Java
  }
  CompilerType compilerType;

  private CompilerErrorEvent(BuildTarget buildTarget, String error, CompilerType compilerType,
      ImmutableSet<String> suggestions) {
    super(EventKey.unique());
    this.target = buildTarget;
    this.error = error;
    this.compilerType = compilerType;
    this.suggestions = suggestions;
  }

  public static CompilerErrorEvent create(
      BuildTarget target,
      String error,
      CompilerType compilerType,
      ImmutableSet<String> suggestions) {
    return new CompilerErrorEvent(target, error, compilerType, suggestions);
  }

  @Override
  protected String getValueString() {
    return "";
  }

  @Override
  public String getEventName() {
    return "CompilerErrorEvent";
  }

  public String getError() {
    return error;
  }

  public String getTarget() {
    return target.getFullyQualifiedName();
  }

  public CompilerType getCompilerType() { return compilerType; }

  public ImmutableSet<String> getSuggestions() { return suggestions; }
}
