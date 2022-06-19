/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.features.project.intellij;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ConfigurationForConfigurationTargets;
import com.facebook.buck.core.model.ErrorTargetConfiguration;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.TargetConfigurationHasher;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Test;

public class TargetConfigurationInfoManagerTest {

  FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final String UNCONFIG_TARGET_HASH =
      TargetConfigurationHasher.hash(UnconfiguredTargetConfiguration.INSTANCE);

  private final String ERROR_CONFIG_TARGET_HASH =
      TargetConfigurationHasher.hash(ErrorTargetConfiguration.INSTANCE);

  private final RuleBasedTargetConfiguration ruleTargetConfig1 =
      RuleBasedTargetConfiguration.of(
          BuildTargetFactory.newInstance(
              "//fake:config1", ConfigurationForConfigurationTargets.INSTANCE));

  private final RuleBasedTargetConfiguration ruleTargetConfig2 =
      RuleBasedTargetConfiguration.of(
          BuildTargetFactory.newInstance(
              "//fake:config2", ConfigurationForConfigurationTargets.INSTANCE));

  private final RuleBasedTargetConfiguration ruleTargetConfig3 =
      RuleBasedTargetConfiguration.of(
          BuildTargetFactory.newInstance(
              "//fake:config3", ConfigurationForConfigurationTargets.INSTANCE));

  private final String RULE_CONFIG_TARGET_HASH1 = TargetConfigurationHasher.hash(ruleTargetConfig1);
  private final String RULE_CONFIG_TARGET_HASH2 = TargetConfigurationHasher.hash(ruleTargetConfig2);
  private final String RULE_CONFIG_TARGET_HASH3 = TargetConfigurationHasher.hash(ruleTargetConfig3);

  @Test
  public void testWriteEmptyGraph() throws IOException {

    ImmutableSet<TargetNode<?>> targetNodes = ImmutableSet.of();

    String targetInfoOutput = prepareTargetConfigurationInfoManagerAndTest(targetNodes);

    assertEquals(toJson(ImmutableSortedMap.of()), targetInfoOutput);
  }

  @Test
  public void testWriteGraph1() throws IOException {

    ImmutableSet<TargetNode<?>> targetNodes = prepareTargetNodeInputsGraph1();

    String targetInfoOutput = prepareTargetConfigurationInfoManagerAndTest(targetNodes);

    assertEquals(
        toJson(
            ImmutableSortedMap.of(
                UnconfiguredTargetConfiguration.NAME,
                ImmutableSortedMap.of(
                    TargetConfigurationInfoManager.CONFIG_HASH_KEY, UNCONFIG_TARGET_HASH))),
        targetInfoOutput);
  }

  @Test
  public void testWriteGraph2() throws IOException {

    ImmutableSet<TargetNode<?>> targetNodes = prepareTargetNodeInputsGraph2();

    String targetInfoOutput = prepareTargetConfigurationInfoManagerAndTest(targetNodes);

    assertEquals(
        toJson(
            ImmutableSortedMap.of(
                ErrorTargetConfiguration.INSTANCE.toString(),
                ImmutableSortedMap.of(
                    TargetConfigurationInfoManager.CONFIG_HASH_KEY, ERROR_CONFIG_TARGET_HASH),
                ruleTargetConfig1.toString(),
                ImmutableSortedMap.of(
                    TargetConfigurationInfoManager.CONFIG_HASH_KEY, RULE_CONFIG_TARGET_HASH1),
                ruleTargetConfig2.toString(),
                ImmutableSortedMap.of(
                    TargetConfigurationInfoManager.CONFIG_HASH_KEY, RULE_CONFIG_TARGET_HASH2),
                ruleTargetConfig3.toString(),
                ImmutableSortedMap.of(
                    TargetConfigurationInfoManager.CONFIG_HASH_KEY, RULE_CONFIG_TARGET_HASH3))),
        targetInfoOutput);
  }

  private String prepareTargetConfigurationInfoManagerAndTest(
      ImmutableSet<TargetNode<?>> targetNodes) throws IOException {
    IjProjectTemplateDataPreparer dataPreparer =
        getDataPreparer(IjModuleGraphTest.createModuleGraph(targetNodes));
    IjProjectConfig projectConfig = getProjectConfig();
    IJProjectCleaner cleaner = new IJProjectCleaner(filesystem);

    TargetConfigurationInfoManager targetConfigInfoManager =
        new TargetConfigurationInfoManager(projectConfig, filesystem);
    targetConfigInfoManager.write(
        dataPreparer.getModulesToBeWritten(), dataPreparer.getAllLibraries(), cleaner);

    return targetConfigInfoManager.readTargetConfigInfoMapAsString();
  }

  private ImmutableSet<TargetNode<?>> prepareTargetNodeInputsGraph1() {
    TargetNode<?> guavaPreBuiltNode =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third_party/java/guava:guava"))
            .setBinaryJar(Paths.get("third_party/java/guava.jar"))
            .build();

    TargetNode<?> guavaTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/guava:guava"))
            .addSrc(Paths.get("third-party/guava/src/Collections.java"))
            .build();

    TargetNode<?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addDep(guavaPreBuiltNode.getBuildTarget())
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .addAnnotationProcessors("//annotation:processor")
            .build();

    return ImmutableSet.of(guavaTargetNode, baseTargetNode, guavaPreBuiltNode);
  }

  private ImmutableSet<TargetNode<?>> prepareTargetNodeInputsGraph2() {
    TargetNode<?> errorConfigNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//fake:error", ErrorTargetConfiguration.INSTANCE))
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .build();

    TargetNode<?> baseTargetNode1 =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//fake:rule1", ruleTargetConfig1))
            .build();

    TargetNode<?> baseTargetNode2 =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//fake:rule2", ruleTargetConfig2))
            .build();

    TargetNode<?> baseTargetNode3 =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//fake:rule3", ruleTargetConfig3))
            .build();

    return ImmutableSet.of(errorConfigNode, baseTargetNode1, baseTargetNode2, baseTargetNode3);
  }

  private IjProjectConfig getProjectConfig() {
    return IjTestProjectConfig.createBuilder(FakeBuckConfig.builder().build()).build();
  }

  private String toJson(ImmutableSortedMap<String, ImmutableSortedMap<String, Object>> map)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (JsonGenerator generator = ObjectMappers.createGenerator(out).useDefaultPrettyPrinter()) {
      generator.writeObject(map);
    }
    return out.toString();
  }

  private IjProjectTemplateDataPreparer getDataPreparer(IjModuleGraph moduleGraph) {
    JavaPackageFinder javaPackageFinder =
        DefaultJavaPackageFinder.createDefaultJavaPackageFinder(filesystem, ImmutableSet.of());
    return new IjProjectTemplateDataPreparer(
        javaPackageFinder, moduleGraph, filesystem, getProjectConfig());
  }
}
