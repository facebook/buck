/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.core.test.rule;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.types.Pair;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import org.immutables.value.Value;

/**
 * A JSON-serializable structure that gets passed to external test runners.
 *
 * <p>NOTE: We're relying on the fact we're using POJOs here and that all the sub-types are
 * JSON-serializable already. Be careful that we don't break serialization when adding item here.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractExternalTestRunnerTestSpec implements JsonSerializable {

  /** @return the build target of this rule. */
  public abstract BuildTarget getTarget();

  /**
   * @return a test-specific type string which classifies the test class, so the external runner
   *     knows how to parse the output.
   */
  public abstract String getType();

  /** @return the command the external test runner must invoke to run the test. */
  public abstract ImmutableList<String> getCommand();

  /** @return the directory from which the external runner should invoke the command. */
  public abstract Path getCwd();

  /**
   * @return coverage threshold and list of source path to be passed the test command for test
   *     coverage.
   */
  public abstract ImmutableList<Pair<Float, ImmutableSet<Path>>> getNeededCoverage();

  /**
   * @return a list of source path to be passed the test command for calculating additional test
   *     coverage.
   */
  public abstract ImmutableSet<Path> getAdditionalCoverageTargets();

  /** @return environment variables the external test runner should provide for the test command. */
  public abstract ImmutableMap<String, String> getEnv();

  /** @return test labels. */
  public abstract ImmutableList<String> getLabels();

  /** @return test contacts. */
  public abstract ImmutableList<String> getContacts();

  /**
   * @return the set of files and directories (may include symlinks or symlink-trees) which *must*
   *     be materialized in order to run this test. This is used by external test runners that wish
   *     to distribute tests to other machines. Some examples of what my contained include:
   *     java_tests: runtime classpath, android_instrumentation_test: test_apk and apk_under_test
   *     paths, python_tests: location of python files if style=inplace
   */
  public abstract ImmutableSet<Path> getRequiredPaths();

  @Override
  public void serialize(JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
      throws IOException {
    jsonGenerator.writeStartObject();
    jsonGenerator.writeStringField("target", getTarget().toString());
    jsonGenerator.writeStringField("type", getType());
    jsonGenerator.writeObjectField("command", getCommand());
    jsonGenerator.writeObjectField("cwd", getCwd().toAbsolutePath().toString());
    jsonGenerator.writeObjectField("env", getEnv());
    if (!getNeededCoverage().isEmpty()) {
      jsonGenerator.writeObjectField(
          "needed_coverage",
          Iterables.transform(
              getNeededCoverage(), input -> ImmutableList.of(input.getFirst(), input.getSecond())));
    }
    if (!getAdditionalCoverageTargets().isEmpty()) {
      jsonGenerator.writeObjectField("additional_coverage_targets", getAdditionalCoverageTargets());
    }
    if (!getRequiredPaths().isEmpty()) {
      jsonGenerator.writeObjectField("required_paths", getRequiredPaths());
    }
    jsonGenerator.writeObjectField(
        "labels",
        getLabels().stream().map(Object::toString).collect(ImmutableList.toImmutableList()));
    jsonGenerator.writeObjectField("contacts", getContacts());
    jsonGenerator.writeEndObject();
  }

  @Override
  public void serializeWithType(
      JsonGenerator jsonGenerator,
      SerializerProvider serializerProvider,
      TypeSerializer typeSerializer)
      throws IOException {
    serialize(jsonGenerator, serializerProvider);
  }
}
