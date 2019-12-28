/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android.dalvik;

import com.facebook.buck.core.util.immutables.BuckStyleValue;

@BuckStyleValue
abstract class DalvikMemberReference {

  public static DalvikMemberReference of(String className, String memberName, String descriptor) {
    return ImmutableDalvikMemberReference.of(className, memberName, descriptor);
  }

  abstract String getClassName();

  abstract String getMemberName();

  abstract String getDescriptor();

  @Override
  public String toString() {
    return getClassName() + "." + getMemberName() + ":" + getDescriptor();
  }
}
