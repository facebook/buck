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

package com.facebook.buck.rules.coercer.concat;

/**
 * The purpose of this {@link com.facebook.buck.rules.coercer.TypeCoercer} to be used together with
 * {@link com.facebook.buck.core.select.impl.SelectorListFactory} to resolve configurable attributes
 * that contain values of JSON-compatible types.
 *
 * <p>This coercer does not transform the objects, but only provides a way to concatenate elements.
 */
public abstract class JsonTypeConcatenatingCoercer implements Concatable<Object> {
  protected JsonTypeConcatenatingCoercer() {}
}
