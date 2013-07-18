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

package com.facebook.buck.java;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.KeystoreProperties;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

public class KeystoreRuleTest {

  private static KeystoreRule createKeystoreRuleForTest() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    return KeystoreRule
        .newKeystoreBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//keystores:debug"))
        .setStore("keystores/debug.keystore")
        .setProperties("keystores/debug.keystore.properties")
        .build(ruleResolver);
  }

  @Test
  public void testObservers() {
    KeystoreRule keystore = createKeystoreRuleForTest();
    assertEquals(BuildRuleType.KEYSTORE, keystore.getType());
    MoreAsserts.assertIterablesEquals(
        ImmutableList.of("keystores/debug.keystore", "keystores/debug.keystore.properties"),
        keystore.getInputsToCompareToOutput());
    assertEquals("keystores/debug.keystore", keystore.getPathToStore());
    assertEquals("keystores/debug.keystore.properties", keystore.getPathToPropertiesFile());
  }

  @Test(expected = IllegalStateException.class)
  public void testAccessingKeystorePropertiesBeforeBuilding() {
    KeystoreRule keystore = createKeystoreRuleForTest();
    keystore.getKeystoreProperties();
  }

  @Test
  @SuppressWarnings("deprecation")
  @Ignore("Enable when KeystoreRule.buildInternal() is fully implemented.")
  public void testBuildInternal() throws IOException {
    // When building the KeystoreRule, it should try to read the properties file:
    Properties properties = new Properties();
    properties.put("key.alias", "my_alias");
    properties.put("key.store.password", "store_password");
    properties.put("key.alias.password", "alias_password");
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem.readPropertiesFile("keystores/debug.keystore.properties"))
        .andReturn(properties);

    // All of this other stuff is to tolerate expected inherited behavior from
    // AbstractCachingBuildRule.
    String pathToSuccessFile = BuckConstant.BIN_DIR + "/keystores/.success/debug";
    expect(projectFilesystem.getFileIfExists(pathToSuccessFile))
        .andReturn(Optional.<File>absent());
    projectFilesystem.createParentDirs(pathToSuccessFile);
    projectFilesystem.writeLinesToPath(ImmutableList.of(Strings.repeat("x", 40)),
        pathToSuccessFile);

    // Mock out the ExecutionContext.
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.getVerbosity()).andReturn(Verbosity.SILENT);
    BuckEventBus eventBus = new BuckEventBus();
    expect(executionContext.getBuckEventBus()).andReturn(eventBus).anyTimes();
    expect(executionContext.getProjectFilesystem()).andReturn(projectFilesystem);

    // Mock out the BuildContext.
    BuildContext buildContext = createMock(BuildContext.class);
    expect(buildContext.getEventBus()).andReturn(eventBus).anyTimes();
    expect(buildContext.getExecutor()).andReturn(MoreExecutors.sameThreadExecutor());
    expect(buildContext.getProjectFilesystem()).andReturn(projectFilesystem);
    expect(buildContext.getArtifactCache()).andReturn(new NoopArtifactCache());
    buildContext.logBuildInfo("[BUILDING %s]", "//keystores:debug");
    expect(buildContext.getStepRunner()).andReturn(new DefaultStepRunner(executionContext));

    // Now perform the interesting part of the test: building the rule!
    replay(projectFilesystem, executionContext, buildContext);

    KeystoreRule keystore = createKeystoreRuleForTest();
    keystore.build(buildContext);
    KeystoreProperties keystoreProperties = keystore.getKeystoreProperties();
    assertEquals("keystores/debug.keystore", keystoreProperties.getKeystore());
    assertEquals("my_alias", keystoreProperties.getAlias());
    assertEquals("store_password", keystoreProperties.getStorepass());
    assertEquals("alias_password", keystoreProperties.getKeypass());

    verify(projectFilesystem, executionContext, buildContext);
  }
}
