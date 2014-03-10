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

package com.facebook.buck.step;

import com.google.common.base.Preconditions;

/**
 * Abstract implementation of {@link Step} that takes the description as a constructor parameter
 * and requires only the implementation of {@link #execute(ExecutionContext)}. This facilitates the
 * creation of an anonymous implementation of {@link Step}.
 */
public abstract class AbstractExecutionStep implements Step {

  private final String description;

  public AbstractExecutionStep(String description) {
    this.description = Preconditions.checkNotNull(description);
  }

  @Override
  public abstract int execute(ExecutionContext context);

  @Override
  public String getShortName() {
    return description;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return description;
  }

}
