/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;

/**
 * ValueTypeInfo for a NonHashableSourcePathContainer. Derives deps+inputs from the contained
 * SourcePath.
 */
public class NonHashableSourcePathContainerValueTypeInfo
    implements ValueTypeInfo<NonHashableSourcePathContainer> {
  @Override
  public <E extends Exception> void visit(
      NonHashableSourcePathContainer value, ValueVisitor<E> visitor) throws E {
    visitor.visitSourcePath(value.getSourcePath());
  }

  @Override
  public <E extends Exception> NonHashableSourcePathContainer create(ValueCreator<E> creator)
      throws E {
    return new NonHashableSourcePathContainer(creator.createSourcePath());
  }
}
