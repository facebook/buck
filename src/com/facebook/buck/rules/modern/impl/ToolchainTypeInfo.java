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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import javax.annotation.Nullable;

/** TypeInfo for all Toolchains. */
public class ToolchainTypeInfo<T extends Toolchain> implements ValueTypeInfo<T> {
  private final Class<T> rawClass;

  public ToolchainTypeInfo(Class<T> rawClass) {
    this.rawClass = rawClass;
  }

  @Override
  public <E extends Exception> void visit(T value, ValueVisitor<E> visitor) throws E {
    visitor.visitString(value.getName());
  }

  @Nullable
  @Override
  public <E extends Exception> T create(ValueCreator<E> creator) throws E {
    return creator
        .createSpecial(ToolchainProvider.class)
        .getByName(creator.createString(), rawClass);
  }
}
