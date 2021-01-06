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

package com.facebook.buck.util.types;

/**
 * Unit type.
 *
 * <p>It is similar to {@link Void} types, except that {@link Void} types cannot be instantiated.
 * This type can be used in generic code, for example, when generic code is not meant to return
 * anything, but {@code null} is not desirable or not allowed.
 */
public enum Unit {
  UNIT
}
