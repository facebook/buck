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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.util.Set;
import java.util.stream.Collectors;
import org.immutables.value.Value;
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
    TypeCoercer<DtoWithImmutableAttribute> typeCoercer =
        (TypeCoercer<DtoWithImmutableAttribute>)
            new DefaultTypeCoercerFactory().typeCoercerForType(dto.getClass());
    typeCoercer.traverse(
        TestCellPathResolver.get(new FakeProjectFilesystem()),
        dto,
        object -> {
          if (object instanceof BuildTarget) {
            collectedTargets.add((BuildTarget) object);
          }
        });

    assertEquals(
        Sets.newHashSet("//a:b", "//a:c", "//a:d"),
        collectedTargets
            .build()
            .stream()
            .map(BuildTarget::getFullyQualifiedName)
            .collect(Collectors.toSet()));
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoAttributeWithCollections {
    abstract Set<BuildTarget> getDeps();

    abstract Set<BuildTarget> getNotDeps();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithImmutableAttribute {
    abstract DtoAttributeWithCollections getDto();

    abstract Set<BuildTarget> getDeps();
  }
}
