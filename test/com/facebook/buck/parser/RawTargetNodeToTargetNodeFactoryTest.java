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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.RawAttributes;
import com.facebook.buck.core.model.targetgraph.RawTargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableRawTargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesTestUtil;
import com.facebook.buck.core.rules.type.RuleType;
import com.facebook.buck.core.select.TestSelectableResolver;
import com.facebook.buck.core.select.impl.DefaultSelectorListResolver;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescriptionArg;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class RawTargetNodeToTargetNodeFactoryTest {

  @Test
  public void testTargetNodeCreatedWithAttributes() {

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//a/b:c");
    Cell cell = new TestCellBuilder().build();
    SourcePath path1 = PathSourcePath.of(cell.getFilesystem(), Paths.get("src1"));
    SourcePath path2 = PathSourcePath.of(cell.getFilesystem(), Paths.get("src2"));
    RawAttributes attributes =
        new RawAttributes(
            ImmutableMap.<String, Object>builder()
                .put("name", "c")
                .put("srcs", ImmutableList.of(path1, path2))
                .build());
    RawTargetNode node =
        ImmutableRawTargetNode.of(
            buildTarget,
            RuleType.of("java_library"),
            attributes,
            ImmutableSet.of(),
            ImmutableSet.of());
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    RawTargetNodeToTargetNodeFactory factory =
        new RawTargetNodeToTargetNodeFactory(
            KnownBuildRuleTypesProvider.of(
                KnownBuildRuleTypesTestUtil.createKnownBuildRuleTypesFactory()),
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory),
            new NoopPackageBoundaryChecker(),
            (file, targetNode) -> {},
            new DefaultSelectorListResolver(new TestSelectableResolver()));

    TargetNode<?> targetNode =
        factory.createTargetNode(
            cell,
            Paths.get("a/b/BUCK"),
            buildTarget,
            node,
            id -> SimplePerfEvent.scope(Optional.empty(), null, null));

    assertEquals(JavaLibraryDescription.class, targetNode.getDescription().getClass());
    JavaLibraryDescriptionArg arg = (JavaLibraryDescriptionArg) targetNode.getConstructorArg();
    assertEquals(
        ImmutableSortedSet.of(
            PathSourcePath.of(cell.getFilesystem(), Paths.get("src1")),
            PathSourcePath.of(cell.getFilesystem(), Paths.get("src2"))),
        arg.getSrcs());
  }
}
