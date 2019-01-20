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
package com.facebook.buck.core.build.engine.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.util.exceptions.ExceptionWithContext;
import com.facebook.buck.util.exceptions.WrapsException;
import java.util.Optional;

/** Exception indicating that building a rule failed. */
public class BuildRuleFailedException extends RuntimeException
    implements WrapsException, ExceptionWithContext {
  private final BuildTarget buildTarget;

  public BuildRuleFailedException(Throwable cause, BuildTarget buildTarget) {
    super(cause);
    this.buildTarget = buildTarget;
  }

  @Override
  public Optional<String> getContext() {
    return Optional.of(String.format("When building rule %s.", buildTarget.toString()));
  }
}
