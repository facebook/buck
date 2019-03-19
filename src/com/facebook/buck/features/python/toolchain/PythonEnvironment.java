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

package com.facebook.buck.features.python.toolchain;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.modern.annotations.CustomClassBehavior;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.modern.CustomClassSerialization;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.facebook.buck.rules.modern.impl.ValueTypeInfoFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import java.nio.file.Path;

/** Tool based on a particular python configuration. */
@CustomClassBehavior(PythonEnvironment.PythonEnvironmentSerialization.class)
public class PythonEnvironment implements Tool {

  private final Path pythonPath;
  private final String configSection;
  @AddToRuleKey private final PythonVersion pythonVersion;

  public PythonEnvironment(Path pythonPath, PythonVersion pythonVersion, String configSection) {
    this.pythonPath = pythonPath;
    this.pythonVersion = pythonVersion;
    this.configSection = configSection;
  }

  public Path getPythonPath() {
    return pythonPath;
  }

  public PythonVersion getPythonVersion() {
    return pythonVersion;
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return ImmutableList.of(pythonPath.toString());
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return ImmutableMap.of();
  }

  /**
   * Serializes PythonEnvironment such that it is recreated from the .buckconfig in the context that
   * it's deserialized in.
   */
  public static class PythonEnvironmentSerialization
      implements CustomClassSerialization<PythonEnvironment> {
    static final ValueTypeInfo<PythonVersion> VERSION_TYPE_INFO =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<PythonVersion>() {});

    @Override
    public <E extends Exception> void serialize(
        PythonEnvironment instance, ValueVisitor<E> serializer) throws E {
      VERSION_TYPE_INFO.visit(instance.pythonVersion, serializer);
      serializer.visitString(instance.configSection);
    }

    @Override
    public <E extends Exception> PythonEnvironment deserialize(ValueCreator<E> deserializer)
        throws E {
      PythonVersion version = VERSION_TYPE_INFO.create(deserializer);
      String configSection = deserializer.createString();
      Path pythonPath =
          deserializer
              .createSpecial(ToolchainProvider.class)
              .getByName(PythonInterpreter.DEFAULT_NAME, PythonInterpreter.class)
              .getPythonInterpreterPath(configSection);
      return new PythonEnvironment(pythonPath, version, configSection);
    }
  }
}
