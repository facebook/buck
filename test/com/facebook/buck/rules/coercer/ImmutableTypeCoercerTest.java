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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.description.arg.DataTransferObject;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public class ImmutableTypeCoercerTest {

  @Test
  public void testTraverseCollectsAllTargets() {
    DtoWithImmutableAttribute dto =
        DtoWithImmutableAttribute.builder()
            .addDeps(BuildTargetFactory.newInstance("//a:b"))
            .setDto(
                DtoAttributeWithCollections.builder()
                    .addDeps(BuildTargetFactory.newInstance("//a:c"))
                    .addNotDeps(BuildTargetFactory.newInstance("//a:d"))
                    .build())
            .build();

    ImmutableList.Builder<BuildTarget> collectedTargets = ImmutableList.builder();
    TypeCoercer<?, DtoWithImmutableAttribute> typeCoercer =
        new DefaultTypeCoercerFactory()
            .typeCoercerForType(TypeToken.of(DtoWithImmutableAttribute.class));
    typeCoercer.traverse(
        TestCellPathResolver.get(new FakeProjectFilesystem()).getCellNameResolver(),
        dto,
        object -> {
          if (object instanceof BuildTarget) {
            collectedTargets.add((BuildTarget) object);
          }
        });

    assertEquals(
        Sets.newHashSet("//a:b", "//a:c", "//a:d"),
        collectedTargets.build().stream()
            .map(BuildTarget::getFullyQualifiedName)
            .collect(Collectors.toSet()));
  }

  @RuleArg
  abstract static class AbstractDtoAttributeWithCollections implements DataTransferObject {
    abstract Set<BuildTarget> getDeps();

    abstract Set<BuildTarget> getNotDeps();
  }

  @RuleArg
  abstract static class AbstractDtoWithImmutableAttribute implements DataTransferObject {
    abstract DtoAttributeWithCollections getDto();

    abstract Set<BuildTarget> getDeps();
  }
}
