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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.ParamsInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.param.CommonParamNames;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.facebook.buck.skylark.parser.context.RecordedRule;
import com.facebook.buck.skylark.parser.pojoizer.BuildFileManifestPojoizer;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkThread;

/**
 * Responsible for creating instances of Skylark functions based on Buck's {@link BaseDescription}s.
 *
 * <p>For example for a {@link com.facebook.buck.jvm.java.JavaLibraryDescription} instance, a
 * Skylark function using snake case of its name prefix will be created - {@code java_library}.
 *
 * <p>Callers can setup created functions in the {@link net.starlark.java.eval.StarlarkThread}.
 */
public class RuleFunctionFactory {

  private static final String BUCK_RULE_DOC_URL_PREFIX = "https://dev.buck.build/rule/";

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
  StarlarkCallable create(BaseDescription<?> ruleClass) {
    String name = DescriptionCache.getRuleType(ruleClass).getName();
    return new StarlarkCallable() {

      @Override
      public String getName() {
        return name;
      }

      @Override
      public Object fastcall(StarlarkThread thread, Object[] positional, Object[] named)
          throws EvalException, InterruptedException {

        if (positional.length != 0) {
          throw new EvalException("rule functions only accept named arguments");
        }

        ParseContext parseContext = ParseContext.getParseContext(thread, name);
        ForwardRelPath basePath = parseContext.getPackageContext().getBasePath();
        RecordedRule recordedRule = populateAttributes(ruleClass, getName(), basePath, named);
        parseContext.recordRule(recordedRule);
        return Starlark.NONE;
      }
    };
  }

  /**
   * Validates attributes passed to the rule and in case any required attribute is not provided,
   * throws an {@link IllegalArgumentException}.
   *
   * @param name The build rule name. (e.g. {@code java_library}).
   */
  private void throwOnMissingRequiredAttribute(Set<ParamName> missingAttributes, String name)
      throws EvalException {
    if (!missingAttributes.isEmpty()) {
      throw new EvalException(
          name
              + " requires "
              + missingAttributes.stream()
                  .map(ParamName::getSnakeCase)
                  .sorted(ParamInfo.NAME_COMPARATOR)
                  .collect(Collectors.joining(" and "))
              + " but they are not provided.\n"
              + "Need help? See "
              + BUCK_RULE_DOC_URL_PREFIX
              + name);
    }
  }

  /**
   * Populates provided {@code builder} with values from {@code kwargs} assuming {@code ruleClass}
   * as the target {@link BaseDescription} class.
   *
   * @param basePath current BUCK file base path
   * @param kwargs The keyword arguments and their values passed to rule function in build file.
   */
  private RecordedRule populateAttributes(
      BaseDescription<?> ruleClass, String name, ForwardRelPath basePath, Object[] kwargs)
      throws EvalException {

    TwoArraysImmutableHashMap.Builder<ParamName, Object> builder =
        TwoArraysImmutableHashMap.builder();

    ParamsInfo allParamInfo =
        typeCoercerFactory
            .getNativeConstructorArgDescriptor(
                (Class<? extends ConstructorArg>) ruleClass.getConstructorArgType())
            .getParamsInfo();

    ImmutableList<String> visibility = ImmutableList.of();
    ImmutableList<String> withinView = ImmutableList.of();

    HashSet<ParamName> missingRequiredParams = new HashSet<>(allParamInfo.getRequiredParams());

    for (int i = 0; i != kwargs.length; i += 2) {
      ParamName paramName = ParamName.bySnakeCase((String) kwargs[i]);
      Object value = kwargs[i + 1];
      Object converted = BuildFileManifestPojoizer.convertToPojo(value);

      missingRequiredParams.remove(paramName);

      if (paramName == CommonParamNames.VISIBILITY) {
        visibility = toListOfString(paramName, converted);
        continue;
      }
      if (paramName == CommonParamNames.WITHIN_VIEW) {
        withinView = toListOfString(paramName, converted);
        continue;
      }
      if (!allParamInfo.getParamInfosByName().containsKey(paramName)) {
        throw new EvalException(paramName + " is not a recognized attribute");
      }
      if (converted != Starlark.NONE) {
        builder.put(paramName, converted);
      }
    }

    throwOnMissingRequiredAttribute(missingRequiredParams, name);

    TwoArraysImmutableHashMap<ParamName, Object> attributes;

    try {
      attributes = builder.build();
    } catch (IllegalStateException e) {
      throw duplicateNamedArgs(name, kwargs);
    }
    return RecordedRule.of(basePath, name, visibility, withinView, attributes);
  }

  private EvalException duplicateNamedArgs(String name, Object[] kwargs) {
    HashSet<String> seen = new HashSet<>();
    LinkedHashSet<String> duplicates = new LinkedHashSet<>();

    for (int i = 0; i < kwargs.length; i += 2) {
      String paramName = (String) kwargs[i];
      if (!seen.add(paramName)) {
        duplicates.add(paramName);
      }
    }

    return new EvalException(
        "duplicate parameters: " + duplicates + " when creating rule: " + name);
  }

  private static ImmutableList<String> toListOfString(ParamName attrName, Object value) {
    if (value == Starlark.NONE) {
      return ImmutableList.of();
    } else if (value instanceof List<?>) {
      List<?> list = (List<?>) value;
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      for (Object o : list) {
        if (!(o instanceof String)) {
          throw new IllegalArgumentException(
              "argument for " + attrName + " must be a list of string, it is " + value);
        }
        builder.add((String) o);
      }
      return builder.build();
    } else {
      throw new IllegalArgumentException(
          "argument for " + attrName + " must be a list of string, it is " + value);
    }
  }
}
