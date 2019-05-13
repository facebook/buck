/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.graph.transformation.model;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import java.util.Objects;

/**
 * A {@link ComputationIdentifier} that identifies uniqueness by the class of the {@link ComputeKey}
 */
public class ClassBasedComputationIdentifier<ResultType extends ComputeResult>
    implements ComputationIdentifier<ResultType> {

  private static final Interner<ClassBasedComputationIdentifier<?>> INTERNER =
      Interners.newWeakInterner();
  private final Class<? extends ComputeKey<ResultType>> clazz;
  private final Class<ResultType> resultTypeClass;
  private final int hash;

  private ClassBasedComputationIdentifier(
      Class<? extends ComputeKey<ResultType>> clazz, Class<ResultType> resultTypeClass) {
    this.clazz = clazz;
    this.resultTypeClass = resultTypeClass;
    hash = Objects.hash(clazz, resultTypeClass);
  }

  /**
   * @param clazz the class of the key
   * @param resultTypeClass the result class type
   * @param <ResultType> the type of the result of the computation this identifies
   * @return a {@link ComputationIdentifier} for the given key and result classes. The instance is
   *     possibly interned.
   */
  @SuppressWarnings("unchecked")
  public static <ResultType extends ComputeResult> ClassBasedComputationIdentifier<ResultType> of(
      Class<? extends ComputeKey<ResultType>> clazz, Class<ResultType> resultTypeClass) {
    return (ClassBasedComputationIdentifier<ResultType>)
        INTERNER.intern(new ClassBasedComputationIdentifier<>(clazz, resultTypeClass));
  }

  @Override
  public Class<ResultType> getResultTypeClass() {
    return resultTypeClass;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ClassBasedComputationIdentifier)) {
      return false;
    }
    return this.clazz == ((ClassBasedComputationIdentifier<?>) obj).clazz
        && resultTypeClass == ((ClassBasedComputationIdentifier<?>) obj).resultTypeClass;
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper("ClassBasedComputationIdentifier")
        .omitNullValues()
        .add("clazz", clazz)
        .add("resultTypeClass", resultTypeClass)
        .toString();
  }
}
