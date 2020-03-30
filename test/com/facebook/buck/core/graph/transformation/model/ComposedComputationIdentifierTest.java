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

package com.facebook.buck.core.graph.transformation.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.core.graph.transformation.impl.ChildrenAdder.LongNode;
import com.facebook.buck.core.graph.transformation.impl.ChildrenSumMultiplier.LongMultNode;
import com.facebook.buck.core.graph.transformation.impl.MyLongNode;
import org.junit.Test;

public class ComposedComputationIdentifierTest {

  @Test
  public void sameClassMeansEqualsAndSameHashcode() {
    ComposedComputationIdentifier<LongMultNode> identifier1 =
        ComposedComputationIdentifier.of(LongNode.IDENTIFIER, LongMultNode.class);
    ComposedComputationIdentifier<LongMultNode> identifier2 =
        ComposedComputationIdentifier.of(LongNode.IDENTIFIER, LongMultNode.class);

    assertEquals(identifier1, identifier2);
    assertEquals(identifier1.hashCode(), identifier2.hashCode());
  }

  @Test
  public void differentClassMeansNotEqual() {
    ComposedComputationIdentifier<LongNode> identifier1 =
        ComposedComputationIdentifier.of(LongMultNode.IDENTIFIER, LongNode.class);
    ComposedComputationIdentifier<LongMultNode> identifier2 =
        ComposedComputationIdentifier.of(LongNode.IDENTIFIER, LongMultNode.class);
    ComposedComputationIdentifier<LongNode> identifier3 =
        ComposedComputationIdentifier.of(LongNode.IDENTIFIER, LongNode.class);

    assertNotEquals(identifier1, identifier2);
    assertNotEquals(identifier1, identifier3);
    assertNotEquals(identifier2, identifier3);
  }

  @Test
  public void sameInstancesAreInterned() {
    ComposedComputationIdentifier<LongNode> identifier1 =
        ComposedComputationIdentifier.of(LongMultNode.IDENTIFIER, LongNode.class);
    ComposedComputationIdentifier<LongNode> identifier2 =
        ComposedComputationIdentifier.of(LongMultNode.IDENTIFIER, LongNode.class);

    assertSame(identifier1, identifier2);
  }

  @Test
  public void computationsEqualIfBaseAndResultTypeTheSame() {
    ComposedComputationIdentifier<LongNode> identifier1 =
        ComposedComputationIdentifier.of(LongMultNode.IDENTIFIER, LongNode.class);

    ComposedComputationIdentifier<MyLongNode> identifier2 =
        ComposedComputationIdentifier.of(identifier1, MyLongNode.class);
    ComposedComputationIdentifier<MyLongNode> identifier3 =
        ComposedComputationIdentifier.of(LongMultNode.IDENTIFIER, MyLongNode.class);

    assertEquals(identifier2, identifier3);
  }
}
