/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.jvm.java.JavaAnnotationProcessorBuilder;
import org.junit.Test;

public class AndroidLibraryTest {

  @Test
  public void testAndroidAnnotation() {
    BuildTarget processorTarget = BuildTargetFactory.newInstance("//java/processor:processor");
    TargetNode<?> processorNode =
        JavaAnnotationProcessorBuilder.createBuilder(processorTarget)
            .addProcessorClass("java/processor/processor.java")
            .build();

    BuildTarget libTarget = BuildTargetFactory.newInstance("//java/lib:lib");
    TargetNode<?> libraryNode =
        AndroidLibraryBuilder.createBuilder(libTarget)
            .addPluginTarget(processorNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(processorNode, libraryNode);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            targetGraph, AndroidLibraryBuilder.createToolchainProviderForAndroidLibrary());

    AndroidLibrary library = (AndroidLibrary) graphBuilder.requireRule(libTarget);

    assertTrue(library.getGeneratedAnnotationSourcePath().isPresent());
    assertTrue(library.hasAnnotationProcessing());
  }
}
