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
import com.google.common.base.Objects;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;

/**
 * A {@link ComputationIdentifier} for a composed {@link
 * com.facebook.buck.core.graph.transformation.GraphComputation}
 *
 * <p>A {@link ComposedComputationIdentifier} is a pair of {@link ClassBasedComputationIdentifier}
 * called the base identifier and a class of {@link ResultType}, which maps to a composed
 * computation that takes the result of the {@link
 * com.facebook.buck.core.graph.transformation.GraphComputation} of the base identifier and uses it
 * to compute a result of {@link ResultType}.
 *
 * <p>e.g. a {@code ComposedComputationIdentifer.of(Key1.IDENTIFIER, Result2.class)} identifies the
 * composed computation of a base computation that computes {@code Key1} to {@code Result1}, then
 * uses {@code Key1} and {@code Result1} to compute {@code Result2}.
 *
 * <p>The {@link ComposedComputationIdentifier} is only identified by the base computation it starts
 * with and the end {@link ResultType}. It does not matter what {@link ComputationIdentifier}s are
 * chained in the middle of getting from the base computation to the target result.
 */
public class ComposedComputationIdentifier<ResultType extends ComputeResult>
    implements ComputationIdentifier<ComposedResult<ComputeKey<ResultType>, ResultType>> {

  private static final Interner<ComposedComputationIdentifier<?>> INTERNER =
      Interners.newWeakInterner();

  private final ClassBasedComputationIdentifier<?> originIdentifier;
  private final Class<ResultType> targetResultClass;
  private final int hash;

  private ComposedComputationIdentifier(
      ClassBasedComputationIdentifier<?> originIdentifier, Class<ResultType> targetResultClass) {
    this.originIdentifier = originIdentifier;
    this.targetResultClass = targetResultClass;
    this.hash = Objects.hashCode(originIdentifier, targetResultClass);
  }

  /**
   * @param originIdentifier the original {@link ClassBasedComputationIdentifier} that starts off a
   *     chain of composition
   * @param targetClass the class of the result type of the computation that this {@link
   *     ComputationIdentifier} identifies
   * @param <ResultType> the type of the result returned by the computation of this {@link
   *     ComputationIdentifier}
   * @return a {@link ComposedComputationIdentifier} that represents the computation that given the
   *     key of the computation of the base identifier and returns the final result
   */
  @SuppressWarnings("unchecked")
  public static <ResultType extends ComputeResult> ComposedComputationIdentifier<ResultType> of(
      ClassBasedComputationIdentifier<?> originIdentifier, Class<ResultType> targetClass) {
    return (ComposedComputationIdentifier<ResultType>)
        INTERNER.intern(new ComposedComputationIdentifier<>(originIdentifier, targetClass));
  }

  /**
   * @param originIdentifier the {@link ComposedComputationIdentifier} that this {@link
   *     ComposedComputationIdentifier} is based off of. The supplied base computation is unwrapped
   *     to the base computation it stores
   * @param targetClass the class of the result type of the computation that this {@link
   *     ComputationIdentifier} identifies
   * @param <ResultType> the type of the result that th computation that this {@link
   *     ComputationIdentifier} returns
   * @return a {@link ComposedComputationIdentifier} that represents the computation that starts at
   *     the base identifier and returns the final result
   */
  @SuppressWarnings("unchecked")
  public static <ResultType extends ComputeResult> ComposedComputationIdentifier<ResultType> of(
      ComposedComputationIdentifier<?> originIdentifier, Class<ResultType> targetClass) {
    return (ComposedComputationIdentifier<ResultType>)
        INTERNER.intern(
            new ComposedComputationIdentifier<>(originIdentifier.originIdentifier, targetClass));
  }

  /**
   * Delegates appropriate to one of the above {@link #of(ClassBasedComputationIdentifier, Class)}
   * or {@link #of(ComposedComputationIdentifier, Class)}
   */
  public static <ResultType extends ComputeResult> ComposedComputationIdentifier<ResultType> of(
      ComputationIdentifier<?> originIdentifier, Class<ResultType> targetClass) {
    // TODO(bobyf): split the types so we don't need these unsafe casts
    if (originIdentifier instanceof ComposedComputationIdentifier) {
      return of(
          ((ComposedComputationIdentifier<?>) originIdentifier).originIdentifier, targetClass);
    }
    if (originIdentifier instanceof ClassBasedComputationIdentifier) {
      return of((ClassBasedComputationIdentifier<?>) originIdentifier, targetClass);
    }
    throw new IllegalStateException(
        String.format("Unexpected ComputationIdentifier type: %s", originIdentifier.getClass()));
  }

  @Override
  @SuppressWarnings("unchecked")
  public Class<ComposedResult<ComputeKey<ResultType>, ResultType>> getResultTypeClass() {
    return (Class<ComposedResult<ComputeKey<ResultType>, ResultType>>)
        (Class<?>) ComposedResult.class;
  }

  /**
   * @return the class of the {@link ResultType} contained in the {@link ComposedResult} of the
   *     computation that this identifier maps to.
   */
  public Class<ResultType> getTargetResultClass() {
    return targetResultClass;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ComposedComputationIdentifier)) {
      return false;
    }
    return originIdentifier.equals(((ComposedComputationIdentifier<?>) o).originIdentifier)
        && targetResultClass == ((ComposedComputationIdentifier<?>) o).targetResultClass;
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper("ComposedComputationIdentifier")
        .omitNullValues()
        .add("originIdentifier", originIdentifier)
        .add("targetResultClass", targetResultClass)
        .toString();
  }
}
