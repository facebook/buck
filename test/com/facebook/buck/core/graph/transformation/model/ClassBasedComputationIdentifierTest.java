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
package com.facebook.buck.core.graph.transformation.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.core.graph.transformation.impl.ChildrenAdder.LongNode;
import com.facebook.buck.core.graph.transformation.impl.ChildrenSumMultiplier.LongMultNode;
import org.junit.Test;

public class ClassBasedComputationIdentifierTest {

  @Test
  public void sameClassMeansEqualsAndSameHashcode() {
    ClassBasedComputationIdentifier<LongNode> identifier1 =
        ClassBasedComputationIdentifier.of(LongNode.class, LongNode.class);
    ClassBasedComputationIdentifier<LongNode> identifier2 =
        ClassBasedComputationIdentifier.of(LongNode.class, LongNode.class);

    assertEquals(identifier1, identifier2);
    assertEquals(identifier1.hashCode(), identifier2.hashCode());
  }

  @Test
  public void differentClassMeansNotEqual() {
    ClassBasedComputationIdentifier<LongNode> identifier1 =
        ClassBasedComputationIdentifier.of(LongNode.class, LongNode.class);
    ClassBasedComputationIdentifier<LongMultNode> identifier2 =
        ClassBasedComputationIdentifier.of(LongMultNode.class, LongMultNode.class);

    assertNotEquals(identifier1, identifier2);
  }

  @Test
  public void sameInstancesAreInterned() {
    ClassBasedComputationIdentifier<LongNode> identifier1 =
        ClassBasedComputationIdentifier.of(LongNode.class, LongNode.class);
    ClassBasedComputationIdentifier<LongNode> identifier2 =
        ClassBasedComputationIdentifier.of(LongNode.class, LongNode.class);

    assertSame(identifier1, identifier2);
  }
}
