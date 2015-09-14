/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.ParamInfo;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.query2.engine.QueryException;

import java.lang.reflect.Field;
import java.nio.file.Path;

public class QueryTargetAccessor {

  private static final TypeCoercerFactory typeCoercerFactory = new TypeCoercerFactory();

  private QueryTargetAccessor() { }

  public static <T> ImmutableSet<QueryTarget> getTargetsInAttribute(
      TargetNode<T> node,
      String attribute)
      throws QueryException {
    try {
      final ImmutableSet.Builder<QueryTarget> builder = ImmutableSortedSet.naturalOrder();
      Field field = node.getConstructorArg().getClass().getField(attribute);
      ParamInfo<T> info = new ParamInfo<>(typeCoercerFactory, field);
      info.traverse(
          new ParamInfo.Traversal() {
            @Override
            public void traverse(Object value) {
              if (value instanceof Path) {
                builder.add(QueryFileTarget.of((Path) value));
              } else if (value instanceof SourcePath) {
                builder.add(extractSourcePath((SourcePath) value));
              } else if (value instanceof HasBuildTarget) {
                builder.add(extractBuildTargetContainer((HasBuildTarget) value));
              }
            }
          },
          node.getConstructorArg()
      );
      return builder.build();
    } catch (NoSuchFieldException e) {
      // Ignore if the field does not exist in this rule.
      return ImmutableSet.of();
    }
  }

  /**
   * Filters the objects in the given attribute that satisfy the given predicate.
   */
  public static <T> ImmutableSet<Object> filterAttributeContents(
      TargetNode<T> node,
      String attribute,
      final Predicate<Object> predicate)
      throws QueryException {
    try {
      final ImmutableSet.Builder<Object> builder = ImmutableSet.builder();
      Field field = node.getConstructorArg().getClass().getField(attribute);
      ParamInfo<T> info = new ParamInfo<>(typeCoercerFactory, field);
      info.traverse(
          new ParamInfo.Traversal() {
            @Override
            public void traverse(Object value) {
              if (predicate.apply(value)) {
                builder.add(value);
              }
            }
          },
          node.getConstructorArg()
      );
      return builder.build();
    } catch (NoSuchFieldException e) {
      // Ignore if the field does not exist in this rule.
      return ImmutableSet.of();
    }
  }

  public static QueryTarget queryTargetFromSourcePath(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return QueryFileTarget.of(((PathSourcePath) sourcePath).getRelativePath());
    } else if (sourcePath instanceof BuildTargetSourcePath) {
      return QueryBuildTarget.of(((BuildTargetSourcePath) sourcePath).getTarget());
    }
    return null;
  }

  public static QueryTarget extractSourcePath(SourcePath sourcePath) {
    return queryTargetFromSourcePath(sourcePath);
  }

  public static QueryTarget extractBuildTargetContainer(HasBuildTarget buildTargetContainer) {
    return QueryBuildTarget.of(buildTargetContainer.getBuildTarget());
  }
}
