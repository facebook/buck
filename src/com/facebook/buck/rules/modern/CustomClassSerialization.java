/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern;

import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.modern.annotations.CustomClassBehaviorTag;
import java.io.IOException;

/**
 * Allows a class to follow specify its serialization/deserialization in terms of calls to the
 * ValueVisitor/ValueCreator.
 */
public interface CustomClassSerialization<T extends AddsToRuleKey> extends CustomClassBehaviorTag {
  void serialize(T instance, ValueVisitor<IOException> serializer) throws IOException;

  T deserialize(ValueCreator<IOException> deserializer) throws IOException;
}
