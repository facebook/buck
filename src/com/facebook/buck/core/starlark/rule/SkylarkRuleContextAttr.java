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
package com.facebook.buck.core.starlark.rule;

import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.ClassObject;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * The struct representing the 'attr' property of the 'ctx' struct passed to a user defined rule's
 * implementation function
 */
public class SkylarkRuleContextAttr implements ClassObject, SkylarkValue {

  private final Map<String, Object> methodParameters;
  private final String methodName;
  private final ImmutableMap<String, Attribute<?>> attributes;
  private final LoadingCache<String, Object> postCoercionTransformValues;

  /**
   * @param methodName
   * @param methodParameters
   * @param attributes
   * @param deps
   */
  public SkylarkRuleContextAttr(
      String methodName,
      Map<String, Object> methodParameters,
      ImmutableMap<String, Attribute<?>> attributes,
      ImmutableMap<BuildTarget, ProviderInfoCollection> deps) {
    this.methodParameters = methodParameters;
    this.methodName = methodName;
    this.attributes = attributes;
    this.postCoercionTransformValues =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<String, Object>() {
                  @Override
                  public Object load(String paramName) {
                    Object coercedValue =
                        Preconditions.checkNotNull(methodParameters.get(paramName));
                    return Preconditions.checkNotNull(attributes.get(paramName))
                        .getPostCoercionTransform()
                        .postCoercionTransform(coercedValue, deps);
                  }
                });
  }

  @Nullable
  @Override
  public Object getValue(String name) {
    if (attributes.containsKey(name)) {
      try {
        return postCoercionTransformValues.get(name);
      } catch (ExecutionException e) {
        throw new BuckUncheckedExecutionException(e);
      }
    }
    return methodParameters.get(name);
  }

  @Override
  public ImmutableCollection<String> getFieldNames() {
    return ImmutableSortedSet.copyOf(methodParameters.keySet());
  }

  @Nullable
  @Override
  public String getErrorMessageForUnknownField(String field) {
    return String.format("Parameter %s not defined for method %s", field, methodName);
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<attr>");
  }
}
