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

package com.facebook.buck.cxx;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.modern.CustomFieldSerialization;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueVisitor;

/** Contains information needed to consume a possibly precompiled header. */
@BuckStyleValue
interface PrecompiledHeaderData extends AddsToRuleKey {
  @AddToRuleKey
  NonHashableSourcePathContainer getHeaderContainer();

  default SourcePath getHeader() {
    return getHeaderContainer().getSourcePath();
  }

  @AddToRuleKey
  SourcePath getInput();

  @CustomFieldBehavior(PrecompiledHeaderSerialization.class)
  @AddToRuleKey
  boolean isPrecompiled();

  /** Disable serialization of precompiled headers. */
  class PrecompiledHeaderSerialization implements CustomFieldSerialization<Boolean> {

    @Override
    public <E extends Exception> void serialize(Boolean value, ValueVisitor<E> serializer)
        throws E {
      if (value) {
        throw new HumanReadableException("Precompiled headers can't be used on different machine");
      }
      serializer.visitBoolean(false);
    }

    @Override
    public <E extends Exception> Boolean deserialize(ValueCreator<E> deserializer) throws E {
      return deserializer.createBoolean();
    }
  }
}
