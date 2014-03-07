/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.dalvik.firstorder;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;

public class FirstOrderHelper {

  private final Iterable<Type> scenarioTypes;
  private final ImmutableSet.Builder<String> resultBuilder;
  private final Map<Type, FirstOrderTypeInfo> knownTypes;

  private FirstOrderHelper(
      Iterable<Type> scenarioTypes,
      ImmutableSet.Builder<String> resultBuilder) {
    this.scenarioTypes = Preconditions.checkNotNull(scenarioTypes);
    this.resultBuilder = Preconditions.checkNotNull(resultBuilder);
    this.knownTypes = Maps.newHashMap();
  }

  public static void addTypesAndDependencies(
      Iterable<Type> scenarioTypes,
      Iterable<ClassNode> allClasses,
      ImmutableSet.Builder<String> classNamesBuilder) {
    FirstOrderHelper helper = new FirstOrderHelper(scenarioTypes, classNamesBuilder);
    helper.addDependencies(allClasses);
  }

  private ImmutableSet<String> addDependencies(Iterable<ClassNode> allClasses) {
    for (ClassNode classNode : allClasses) {
      FirstOrderVisitorContext context = new FirstOrderVisitorContext();
      classNode.accept(context.classVisitor);

      FirstOrderTypeInfo info = context.builder.build();
      knownTypes.put(info.type, info);
    }

    // TODO(user): consider adding events here that allow developers to track
    // how many non android.* and java.* classes went unrecognized over time.
    //
    // Not all types will be known.  This includes types from Android, java
    // runtime libraries, and any types that have been removed from the application
    // since the scenario ran.

    for (Type type : scenarioTypes) {
      addFirstOrderTypes(type);
    }

    return resultBuilder.build();
  }

  private void addFirstOrderTypes(Type type) {
    addTypeAndSupers(type);

    FirstOrderTypeInfo info = knownTypes.get(type);
    if (info != null) {
      for (Type dependency : info.observedDependencies) {
        addTypeAndSupers(dependency);
      }
    }
  }

  private void addTypeAndSupers(Type type) {
    addType(type);

    FirstOrderTypeInfo info = knownTypes.get(type);
    if (info != null) {
      addTypeAndSupers(info.superType);

      for (Type interfaceType : info.interfaceTypes) {
        addTypeAndSupers(interfaceType);
      }
    }
  }

  private void addType(Type type) {
    resultBuilder.add(type.getInternalName());
  }
}
