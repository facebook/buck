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

package com.facebook.buck.skylark.function;

import com.facebook.buck.skylark.parser.context.ReadConfigContext;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

/**
 * Abstract class containing function definitions shared by {@link SkylarkBuildModule} and {@link
 * SkylarkPackageModule}.
 *
 * <p>Note: @SkylarkModule does not support having the same name for multiple classes in {@link
 * com.google.devtools.build.lib.syntax.Starlark#addModule(ImmutableMap.Builder, Object)} and since
 * we want the shared functions to also be under "native", we must subclass.
 */
public abstract class AbstractSkylarkFunctions {

  /**
   * Exposes a {@code read_config} for Skylark parser.
   *
   * <p>This is a temporary solution to simplify migration from Python DSL to Skylark and allows
   * clients to query values from {@code .buckconfig} files and {@code --config} command line
   * arguments.
   *
   * <p>Example, when buck is invoked with {@code --config user.value=my_value} an invocation of
   * {@code read_config("user", "value", "default_value")} will return {@code my_value}.
   */
  @SkylarkCallable(
      name = "read_config",
      doc =
          "Returns a configuration value of <code>.buckconfig</code> or <code>--config</code> flag."
              + " For example, <code>read_config('foo', 'bar', 'baz')</code> returns"
              + " <code>bazz</code> if Buck is invoked with <code>--config foo.bar=bazz</code> flag.",
      parameters = {
        @Param(
            name = "section",
            type = String.class,
            doc = "the name of the .buckconfig section with the desired value."),
        @Param(
            name = "field",
            type = String.class,
            doc = "the name of the .buckconfig field with the desired value."),
        @Param(
            name = "defaultValue",
            noneable = true,
            type = String.class,
            defaultValue = "None",
            doc = "the value to return if the desired value is not set in the .buckconfig."),
      },
      documented = false, // this is an API that we should remove once select is available
      allowReturnNones = true,
      useLocation = true,
      useStarlarkThread = true)
  public Object readConfig(
      String section, String field, Object defaultValue, Location loc, StarlarkThread env)
      throws EvalException {
    ReadConfigContext configContext = ReadConfigContext.getContext(env, loc);
    @Nullable
    String value = configContext.getRawConfig().getOrDefault(section, ImmutableMap.of()).get(field);

    configContext.recordReadConfigurationOption(section, field, value);
    return value != null ? value : defaultValue;
  }

  @SkylarkCallable(
      name = "sha256",
      doc = "Computes a sha256 digest for a string. Returns the hex representation of the digest.",
      parameters = {@Param(name = "value", type = String.class, named = true)})
  public String sha256(String value) {
    return Hashing.sha256().hashString(value, StandardCharsets.UTF_8).toString();
  }
}
