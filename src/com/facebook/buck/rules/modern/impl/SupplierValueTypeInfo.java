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

import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.google.common.base.Suppliers;
import java.util.function.Supplier;

/**
 * ValueTypeInfo for Suppliers. Visiting is just forwarded to the get()-returned value, and creation
 * uses Suppliers.ofInstance().
 */
public class SupplierValueTypeInfo<T> implements ValueTypeInfo<Supplier<T>> {
  private final ValueTypeInfo<T> innerType;

  public SupplierValueTypeInfo(ValueTypeInfo<T> innerType) {
    this.innerType = innerType;
  }

  @Override
  public <E extends Exception> void visit(Supplier<T> value, ValueVisitor<E> visitor) throws E {
    innerType.visit(value.get(), visitor);
  }

  @Override
  public <E extends Exception> Supplier<T> create(ValueCreator<E> creator) throws E {
    return Suppliers.ofInstance(innerType.createNotNull(creator));
  }
}
