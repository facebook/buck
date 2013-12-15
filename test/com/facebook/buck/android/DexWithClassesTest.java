/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.facebook.buck.java.FakeJavaLibraryRule;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;

import org.junit.Test;

import java.nio.file.Paths;

public class DexWithClassesTest {

  @Test
  public void testIntermediateDexRuleToDexWithClasses() {
    BuildTarget javaLibraryTarget = new BuildTarget("//java/com/example", "lib");
    JavaLibraryRule javaLibrary = new FakeJavaLibraryRule(javaLibraryTarget) {
      @Override
      public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
        return ImmutableSortedMap.of("com/example/Main", HashCode.fromString("cafebabe"));
      }
    };

    BuildTarget buildTarget = new BuildTarget("//java/com/example", "lib", "dex");
    DexProducedFromJavaLibraryThatContainsClassFiles dexFromJavaLibrary =
        new DexProducedFromJavaLibraryThatContainsClassFiles(buildTarget, javaLibrary) {
      @Override
      public int getLinearAllocEstimate() {
        return 1600;
      }
    };

    IntermediateDexRule intermediateDexRule = new IntermediateDexRule(
        dexFromJavaLibrary,
        new FakeBuildRuleParams(buildTarget));

    DexWithClasses dexWithClasses = DexWithClasses.TO_DEX_WITH_CLASSES.apply(intermediateDexRule);
    assertEquals(Paths.get("buck-out/gen/java/com/example/lib#dex.dex.jar"),
        dexWithClasses.getPathToDexFile());
    assertEquals(ImmutableSet.of("com/example/Main"), dexWithClasses.getClassNames());
    assertEquals(1600, dexWithClasses.getSizeEstimate());
  }

  @Test
  public void testIntermediateDexRuleToDexWithClassesWhenIntermediateDexHasNoClasses() {
    BuildTarget javaLibraryTarget = new BuildTarget("//java/com/example", "lib");
    JavaLibraryRule javaLibrary = new FakeJavaLibraryRule(javaLibraryTarget) {
      @Override
      public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
        return ImmutableSortedMap.of();
      }
    };

    BuildTarget buildTarget = new BuildTarget("//java/com/example", "lib", "dex");
    DexProducedFromJavaLibraryThatContainsClassFiles dexFromJavaLibrary =
        new DexProducedFromJavaLibraryThatContainsClassFiles(buildTarget, javaLibrary) {
      @Override
      public int getLinearAllocEstimate() {
        return 1600;
      }
    };

    IntermediateDexRule intermediateDexRule = new IntermediateDexRule(
        dexFromJavaLibrary,
        new FakeBuildRuleParams(buildTarget));

    DexWithClasses dexWithClasses = DexWithClasses.TO_DEX_WITH_CLASSES.apply(intermediateDexRule);
    assertNull(
        "If the JavaLibraryRule does not produce any .class files, " +
            "then DexWithClasses.TO_DEX_WITH_CLASSES should return null.",
        dexWithClasses);
  }
}
