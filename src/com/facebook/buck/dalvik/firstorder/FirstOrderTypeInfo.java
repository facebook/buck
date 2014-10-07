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
import com.google.common.collect.Sets;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;

import java.util.Set;

import javax.annotation.Nullable;

class FirstOrderTypeInfo {

  final Type type;
  final Type superType;

  final ImmutableSet<Type> interfaceTypes;
  final ImmutableSet<Type> observedDependencies;

  private FirstOrderTypeInfo(
      Type type,
      Type superType,
      Iterable<Type> interfaceTypes,
      Iterable<Type> observedDependencies) {
    this.type = type;
    this.superType = superType;
    this.interfaceTypes = ImmutableSet.copyOf(interfaceTypes);
    this.observedDependencies = ImmutableSet.copyOf(observedDependencies);
  }

  static Builder builder() {
    return new Builder();
  }

  static class Builder {

    @Nullable
    private Type mType;

    @Nullable
    private Type mSuperType;

    private final Set<Type> mInterfaceTypes = Sets.newHashSet();
    private final Set<Type> mObservedDependencies = Sets.newHashSet();

    FirstOrderTypeInfo build() {
      Preconditions.checkNotNull(mType);
      Preconditions.checkNotNull(mSuperType);
      return new FirstOrderTypeInfo(
          mType,
          mSuperType,
          mInterfaceTypes,
          mObservedDependencies);
    }

    Builder setTypeInternalName(String internalName) {
      Preconditions.checkState(mType == null);
      mType = Type.getObjectType(internalName);
      return this;
    }

    Builder setSuperTypeInternalName(String internalName) {
      Preconditions.checkState(mSuperType == null);
      Type type = Type.getObjectType(internalName);
      mSuperType = type;
      mObservedDependencies.add(type);
      return this;
    }

    Builder addValue(Object value) {
      if (value instanceof Type) {
        addDependency((Type) value);
      } else if (value instanceof Handle) {
        Handle h = (Handle) value;
        addDependencyInternalName(h.getOwner());
        addDependencyDesc(h.getDesc());
      }
      return this;
    }

    Builder addInterfaceTypeInternalName(String internalName) {
      Type type = Type.getObjectType(internalName);
      mInterfaceTypes.add(type);
      mObservedDependencies.add(type);
      return this;
    }

    Builder addDependencyDesc(String desc) {
      if (desc != null) {
        addDependency(Type.getType(desc));
      }
      return this;
    }

    Builder addDependencyInternalName(String internalName) {
      if (internalName != null) {
        addDependency(Type.getObjectType(internalName));
      }
      return this;
    }

    Builder addDependency(Type type) {
      switch (type.getSort()) {
        case Type.OBJECT:
          mObservedDependencies.add(type);
          break;

        case Type.ARRAY:
          addDependency(type.getElementType());
          break;

        case Type.METHOD:
          addDependency(type.getReturnType());
          for (Type argumentType : type.getArgumentTypes()) {
            addDependency(argumentType);
          }
          break;

        default:
          break;
      }
      return this;
    }
  }
}
