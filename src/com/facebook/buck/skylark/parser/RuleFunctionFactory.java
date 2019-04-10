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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.rules.coercer.CoercedTypeCache;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Runtime;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Responsible for creating instances of Skylark functions based on Buck's {@link BaseDescription}s.
 *
 * <p>For example for a {@link com.facebook.buck.jvm.java.JavaLibraryDescription} instance, a
 * Skylark function using snake case of its name prefix will be created - {@code java_library}.
 *
 * <p>Callers can setup created functions in the {@link Environment}.
 */
public class RuleFunctionFactory {

  private static final ImmutableSet<String> IMPLICIT_ATTRIBUTES =
      ImmutableSet.of("visibility", "within_view");
  // URL prefix for all build rule documentation pages
  private static final String BUCK_RULE_DOC_URL_PREFIX = "https://buckbuild.com/rule/";

  private final TypeCoercerFactory typeCoercerFactory;

  public RuleFunctionFactory(TypeCoercerFactory typeCoercerFactory) {
    this.typeCoercerFactory = typeCoercerFactory;
  }

  /**
   * Create a Skylark function definition for the {@code ruleClass} rule.
   *
   * <p>This creates functions like @{code java_library}. All they do is capture passed attribute
   * values in a map and records them in a {@link ParseContext}. They can be queried using {@link
   * ParseContext#getRecordedRules()}.
   *
   * @param ruleClass The name of the rule to to define.
   * @return Skylark function to handle the Buck rule.
   */
  BuiltinFunction create(BaseDescription<?> ruleClass) {
    String name = DescriptionCache.getRuleType(ruleClass).getName();
    return new BuiltinFunction(
        name, FunctionSignature.KWARGS, BuiltinFunction.USE_AST_ENV, /*isRule=*/ true) {

      @SuppressWarnings({"unused"})
      public Runtime.NoneType invoke(
          Map<String, Object> kwargs, FuncallExpression ast, Environment env) throws EvalException {
        ParseContext parseContext = ParseContext.getParseContext(env, ast);
        ImmutableMap.Builder<String, Object> builder =
            ImmutableMap.<String, Object>builder()
                .put(
                    "buck.base_path",
                    parseContext
                        .getPackageContext()
                        .getPackageIdentifier()
                        .getPackageFragment()
                        .getPathString())
                .put("buck.type", name);
        ImmutableMap<String, ParamInfo> allParamInfo =
            CoercedTypeCache.INSTANCE.getAllParamInfo(
                typeCoercerFactory, ruleClass.getConstructorArgType());
        populateAttributes(kwargs, builder, allParamInfo);
        throwOnMissingRequiredAttribute(kwargs, allParamInfo, getName(), ast);
        parseContext.recordRule(builder.build(), ast);
        return Runtime.NONE;
      }
    };
  }

  /**
   * Validates attributes passed to the rule and in case any required attribute is not provided,
   * throws an {@link IllegalArgumentException}.
   *
   * @param kwargs The keyword arguments passed to the rule.
   * @param allParamInfo The mapping from build rule attributes to their information.
   * @param name The build rule name. (e.g. {@code java_library}).
   * @param ast The abstract syntax tree of the build rule function invocation.
   */
  private void throwOnMissingRequiredAttribute(
      Map<String, Object> kwargs,
      ImmutableMap<String, ParamInfo> allParamInfo,
      String name,
      FuncallExpression ast)
      throws EvalException {
    ImmutableList<ParamInfo> missingAttributes =
        allParamInfo.values().stream()
            .filter(param -> !param.isOptional() && !kwargs.containsKey(param.getPythonName()))
            .collect(ImmutableList.toImmutableList());
    if (!missingAttributes.isEmpty()) {
      throw new EvalException(
          ast.getLocation(),
          name
              + " requires "
              + missingAttributes.stream()
                  .map(ParamInfo::getPythonName)
                  .collect(Collectors.joining(" and "))
              + " but they are not provided.",
          BUCK_RULE_DOC_URL_PREFIX + name);
    }
  }

  /**
   * Populates provided {@code builder} with values from {@code kwargs} assuming {@code ruleClass}
   * as the target {@link BaseDescription} class.
   *
   * @param kwargs The keyword arguments and their values passed to rule function in build file.
   * @param builder The map builder used for storing extracted attributes and their values.
   * @param allParamInfo The parameter information for every build rule attribute.
   */
  private void populateAttributes(
      Map<String, Object> kwargs,
      ImmutableMap.Builder<String, Object> builder,
      ImmutableMap<String, ParamInfo> allParamInfo) {
    for (Map.Entry<String, Object> kwargEntry : kwargs.entrySet()) {
      String paramName =
          CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, kwargEntry.getKey());
      if (!allParamInfo.containsKey(paramName)
          && !(IMPLICIT_ATTRIBUTES.contains(kwargEntry.getKey()))) {
        throw new IllegalArgumentException(kwargEntry.getKey() + " is not a recognized attribute");
      }
      if (Runtime.NONE.equals(kwargEntry.getValue())) {
        continue;
      }
      builder.put(paramName, kwargEntry.getValue());
    }
  }
}
