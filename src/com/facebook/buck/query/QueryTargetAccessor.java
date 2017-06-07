/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.query;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.CoercedTypeCache;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

public class QueryTargetAccessor {

  private static final TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();

  private QueryTargetAccessor() {}

  public static <T> ImmutableSet<QueryTarget> getTargetsInAttribute(
      TargetNode<T, ?> node, String attribute) {
    Class<?> constructorArgClass = node.getConstructorArg().getClass();
    ParamInfo info =
        CoercedTypeCache.INSTANCE
            .getAllParamInfo(typeCoercerFactory, constructorArgClass)
            .get(attribute);
    if (info == null) {
      // Ignore if the field does not exist in this rule.
      return ImmutableSet.of();
    }
    final ImmutableSet.Builder<QueryTarget> builder = ImmutableSortedSet.naturalOrder();
    info.traverse(
        value -> {
          if (value instanceof Path) {
            builder.add(QueryFileTarget.of((Path) value));
          } else if (value instanceof SourcePath) {
            builder.add(extractSourcePath((SourcePath) value));
          } else if (value instanceof BuildTarget) {
            builder.add(extractBuildTargetContainer((BuildTarget) value));
          }
        },
        node.getConstructorArg());
    return builder.build();
  }

  /** Filters the objects in the given attribute that satisfy the given predicate. */
  public static <T> ImmutableSet<Object> filterAttributeContents(
      TargetNode<T, ?> node, String attribute, final Predicate<Object> predicate) {
    Class<?> constructorArgClass = node.getConstructorArg().getClass();
    ParamInfo info =
        CoercedTypeCache.INSTANCE
            .getAllParamInfo(typeCoercerFactory, constructorArgClass)
            .get(attribute);
    if (info == null) {
      // Ignore if the field does not exist in this rule.
      return ImmutableSet.of();
    }
    final ImmutableSet.Builder<Object> builder = ImmutableSet.builder();
    info.traverse(
        value -> {
          if (predicate.apply(value)) {
            builder.add(value);
          }
        },
        node.getConstructorArg());
    return builder.build();
  }

  public static QueryTarget extractSourcePath(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return QueryFileTarget.of(((PathSourcePath) sourcePath).getRelativePath());
    } else if (sourcePath instanceof BuildTargetSourcePath) {
      return QueryBuildTarget.of(((BuildTargetSourcePath) sourcePath).getTarget());
    }
    throw new HumanReadableException("Unsupported source path type: %s", sourcePath.getClass());
  }

  public static QueryTarget extractBuildTargetContainer(BuildTarget buildTargetContainer) {
    return QueryBuildTarget.of(buildTargetContainer);
  }
}
