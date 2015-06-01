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

package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.rules.AppendableRuleKeyCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.EmptyRuleKeyBuilder;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyPair;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ExternalJavacTest extends EasyMockSupport {
  private static final Path PATH_TO_SRCS_LIST = Paths.get("srcs_list");
  public static final ImmutableSet<Path> SOURCE_PATHS = ImmutableSet.of(Paths.get("foobar.java"));

  @Rule
  public DebuggableTemporaryFolder root = new DebuggableTemporaryFolder();

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();


  @Test
  public void testJavacCommand() {
    ExecutionContext context = TestExecutionContext.newInstance();

    ExternalJavac firstOrder = createTestStep();
    ExternalJavac warn = createTestStep();
    ExternalJavac transitive = createTestStep();

    assertEquals("fakeJavac -source 6 -target 6 -g -d . -classpath foo.jar @" + PATH_TO_SRCS_LIST,
        firstOrder.getDescription(
            context,
            getArgs().add("foo.jar").build(),
            SOURCE_PATHS,
            Optional.of(PATH_TO_SRCS_LIST)));
    assertEquals("fakeJavac -source 6 -target 6 -g -d . -classpath foo.jar @" + PATH_TO_SRCS_LIST,
        warn.getDescription(
            context,
            getArgs().add("foo.jar").build(),
            SOURCE_PATHS,
            Optional.of(PATH_TO_SRCS_LIST)));
    assertEquals("fakeJavac -source 6 -target 6 -g -d . -classpath bar.jar" + File.pathSeparator +
        "foo.jar @" + PATH_TO_SRCS_LIST,
        transitive.getDescription(
            context,
            getArgs().add("bar.jar" + File.pathSeparator + "foo.jar").build(),
            SOURCE_PATHS,
            Optional.of(PATH_TO_SRCS_LIST)));
  }

  @Test
  public void externalJavacWillHashTheExternalIfNoVersionInformationIsReturned()
      throws IOException {
    Path javac = Files.createTempFile("fake", "javac");
    javac.toFile().deleteOnExit();

    Map<Path, HashCode> hashCodes = ImmutableMap.of(javac, Hashing.sha1().hashInt(42));
    FakeFileHashCache fileHashCache = new FakeFileHashCache(hashCodes);
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    AppendableRuleKeyCache appendableRuleKeyCache =
        new AppendableRuleKeyCache(pathResolver, fileHashCache);
    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//example:target").build();
    BuildRule buildRule = new NoopBuildRule(params, pathResolver);
    FakeRuleKeyBuilderFactory fakeRuleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(fileHashCache, pathResolver);

    RuleKey javacKey = EmptyRuleKeyBuilder.newInstance(
        pathResolver,
        fileHashCache,
        appendableRuleKeyCache)
        .setReflectively("javac", javac)
        .build()
        .getRuleKeyWithoutDeps();
    RuleKey.Builder builder = fakeRuleKeyBuilderFactory.newInstance(buildRule);
    builder.setReflectively("key.appendableSubKey", javacKey);
    RuleKeyPair expected = builder.build();

    builder = fakeRuleKeyBuilderFactory.newInstance(buildRule);
    ExternalJavac compiler = new ExternalJavac(javac, Optional.<JavacVersion>absent());
    builder.setReflectively("key", compiler);
    RuleKeyPair seen = builder.build();

    assertEquals(expected, seen);
  }

  @Test
  public void externalJavacWillHashTheJavacVersionIfPresent()
      throws IOException {
    Path javac = Files.createTempFile("fake", "javac");
    javac.toFile().deleteOnExit();
    JavacVersion javacVersion = JavacVersion.of("mozzarella");

    Map<Path, HashCode> hashCodes = ImmutableMap.of(javac, Hashing.sha1().hashInt(42));
    FakeFileHashCache fileHashCache = new FakeFileHashCache(hashCodes);
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    AppendableRuleKeyCache appendableRuleKeyCache =
        new AppendableRuleKeyCache(pathResolver, fileHashCache);
    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//example:target").build();
    BuildRule buildRule = new NoopBuildRule(params, pathResolver);
    FakeRuleKeyBuilderFactory fakeRuleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(fileHashCache, pathResolver);

    RuleKey javacKey = EmptyRuleKeyBuilder.newInstance(
        pathResolver,
        fileHashCache,
        appendableRuleKeyCache)
        .setReflectively("javac.version", javacVersion.toString())
        .build()
        .getRuleKeyWithoutDeps();
    RuleKey.Builder builder = fakeRuleKeyBuilderFactory.newInstance(buildRule);
    builder.setReflectively("key.appendableSubKey", javacKey);
    RuleKeyPair expected = builder.build();

    builder = fakeRuleKeyBuilderFactory.newInstance(buildRule);
    ExternalJavac compiler = new ExternalJavac(javac, Optional.of(javacVersion));
    builder.setReflectively("key", compiler);
    RuleKeyPair seen = builder.build();

    assertEquals(expected, seen);
  }

  private ImmutableList.Builder<String> getArgs() {
    return ImmutableList.<String>builder().add(
          "-source", "6",
          "-target", "6",
          "-g",
          "-d", ".",
          "-classpath");
  }

  private ExternalJavac createTestStep() {
    Path fakeJavac = Paths.get("fakeJavac");
    return new ExternalJavac(
        fakeJavac, Optional.of(JavacVersion.of("unknown")));
  }
}
