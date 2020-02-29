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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.description.arg.DataTransferObject;
import com.google.common.reflect.TypeToken;

public interface TypeCoercerFactory {

  <T> TypeCoercer<?, T> typeCoercerForType(TypeToken<T> type);

  /**
   * Returns an unpopulated DTO object, and the build method which must be called with it when it is
   * finished being populated.
   */
  <T extends DataTransferObject> DataTransferObjectDescriptor<T> getConstructorArgDescriptor(
      Class<T> dtoType);
}
