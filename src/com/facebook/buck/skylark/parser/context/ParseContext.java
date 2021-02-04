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

package com.facebook.buck.skylark.parser.context;

import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.rules.param.CommonParamNames;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;

/**
 * Tracks parse context.
 *
 * <p>This class provides API to record information retrieved while parsing a build or package file
 * like parsed rules or a package definition.
 */
public class ParseContext {
  private @Nullable PackageMetadata pkg;

  private final Map<String, RecordedRule> rawRules;
  private final PackageContext packageContext;

  public ParseContext(PackageContext packageContext) {
    this.rawRules = new HashMap<>();
    this.packageContext = packageContext;
  }

  /** Records the parsed {@code rawPackage}. */
  public void recordPackage(PackageMetadata pkg) throws EvalException {
    Preconditions.checkState(rawRules.isEmpty(), "Package files cannot contain rules.");
    if (this.pkg != null) {
      throw new EvalException(String.format("Only one package is allow per package file."));
    }
    this.pkg = pkg;
  }

  /** Records the parsed {@code rawRule}. */
  public void recordRule(RecordedRule rawRule) throws EvalException {
    Preconditions.checkState(pkg == null, "Build files cannot contain package definitions.");
    Object nameObject =
        Objects.requireNonNull(
            rawRule.getRawRule().get(CommonParamNames.NAME), "Every target must have a name.");
    if (!(nameObject instanceof String)) {
      throw new IllegalArgumentException(
          "Target name must be string, it is "
              + nameObject
              + " ("
              + nameObject.getClass().getName()
              + ")");
    }
    String name = (String) nameObject;
    if (rawRules.containsKey(name)) {
      throw new EvalException(
          String.format(
              "Cannot register rule %s:%s of type %s with content %s again.",
              rawRule.getBasePath(), name, rawRule.getBuckType(), rawRule.getRawRule()));
    }
    rawRules.put(name, rawRule);
  }

  /** @return The package in the parsed package file if defined. */
  public PackageMetadata getPackage() {
    if (pkg == null) {
      return PackageMetadata.EMPTY_SINGLETON;
    }
    return pkg;
  }

  /**
   * @return The list of raw build rules discovered in parsed build file. Raw rule is presented as a
   *     map with attributes as keys and parameters as values.
   */
  public TwoArraysImmutableHashMap<String, RecordedRule> getRecordedRules() {
    return TwoArraysImmutableHashMap.copyOf(rawRules);
  }

  /** @return {@code true} if the rule with provided name exists, {@code false} otherwise. */
  public boolean hasRule(String name) {
    return rawRules.containsKey(name);
  }

  /** Returns a context of the package currently being parsed. */
  public PackageContext getPackageContext() {
    return packageContext;
  }

  /** Get the {@link ParseContext} by looking up in the environment. */
  public static ParseContext getParseContext(StarlarkThread env, String name) throws EvalException {
    @Nullable ParseContext value = env.getThreadLocal(ParseContext.class);
    if (value == null) {
      // if PARSE_CONTEXT is missing, we're not called from a build file. This happens if someone
      // uses native.some_func() in the wrong place.
      throw new EvalException(
          "Top-level invocations of "
              + name
              + " are not allowed in .bzl files. Wrap it in a macro and call it from a BUCK file.");
    }
    return value;
  }

  public void setup(StarlarkThread env) {
    env.setThreadLocal(ParseContext.class, this);
  }
}
