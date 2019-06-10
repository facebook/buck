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
package com.facebook.buck.skylark.function.attr;

import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.SkylarkDict;

/**
 * Simple wrapper object to hold onto Attribute objects and get around some type erasure problems in
 * {@link SkylarkDict#getContents(Class, Class, String)}. If using {@link Attribute}&gt;T&lt;, then
 * a {@link Class}&lt;T&gt; cannot be instantiated. If the generic {@link Attribute} type is passed
 * and used, then it is a compile error. We need {@link SkylarkDict#getContents(Class, Class,
 * String)} to succeed in order to validate the types of the objects in that ditcionary, so add an
 * intermediate class. This is terrible.
 */
public interface AttributeHolder extends SkylarkValue {
  /** Get the actual attribute object */
  Attribute<?> getAttribute();
}
