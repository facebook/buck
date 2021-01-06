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

package com.facebook.buck.parser.function;

import com.facebook.buck.core.description.arg.DataTransferObject;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.visibility.VisibilityAttributes;
import com.facebook.buck.skylark.function.SkylarkRuleFunctions;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Supplier;
import org.stringtemplate.v4.AutoIndentWriter;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.STGroupFile;

/**
 * Used to generate a function for use within buck.py for the rule described by a {@link
 * com.facebook.buck.core.description.Description}.
 */
public class BuckPyFunction {

  /**
   * Properties from the JSON produced by {@code buck.py} that start with this prefix do not
   * correspond to build rule arguments specified by the user. Instead, they contain internal-only
   * metadata, so they should not be printed when the build rule is reproduced.
   */
  public static final String INTERNAL_PROPERTY_NAME_PREFIX = "buck.";

  /**
   * The name of the property in the JSON produced by {@code buck.py} that identifies the type of
   * the build rule being defined.
   */
  public static final String TYPE_PROPERTY_NAME = INTERNAL_PROPERTY_NAME_PREFIX + "type";

  public static final String BUCK_PY_FUNCTION_TEMPLATE = "BuckPyFunction.stg";

  private static final Supplier<STGroup> buckPyFunctionTemplate =
      MoreSuppliers.memoize(
          () ->
              new STGroupFile(
                  Resources.getResource(BuckPyFunction.class, BUCK_PY_FUNCTION_TEMPLATE),
                  "UTF-8",
                  '<',
                  '>'));
  private final TypeCoercerFactory typeCoercerFactory;

  public BuckPyFunction(TypeCoercerFactory typeCoercerFactory) {
    this.typeCoercerFactory = typeCoercerFactory;
  }

  /**
   * Create a Python function definition for given rule type and parameters described by DTO type.
   */
  public String toPythonFunction(RuleType type, Class<? extends DataTransferObject> dtoClass) {
    ImmutableList.Builder<StParamInfo> mandatory = ImmutableList.builder();
    ImmutableList.Builder<StParamInfo> optional = ImmutableList.builder();
    for (ParamInfo<?> param :
        typeCoercerFactory.getConstructorArgDescriptor(dtoClass).getParamInfos().values().stream()
            .sorted(Comparator.comparing(ParamInfo::getName))
            .collect(ImmutableList.toImmutableList())) {
      if (isSkippable(param)) {
        continue;
      }
      if (param.isOptional()) {
        optional.add(new StParamInfo(param));
      } else {
        mandatory.add(new StParamInfo(param));
      }
    }
    optional.add(
        StParamInfo.ofOptionalValue(
            VisibilityAttributes.VISIBILITY, VisibilityAttributes.VISIBILITY));
    optional.add(
        StParamInfo.ofOptionalValue(
            VisibilityAttributes.WITHIN_VIEW, VisibilityAttributes.WITHIN_VIEW));

    STGroup group = buckPyFunctionTemplate.get();
    ST st;
    // STGroup#getInstanceOf may not be thread safe.
    // See discussion in: https://github.com/antlr/stringtemplate4/issues/61
    synchronized (group) {
      st = group.getInstanceOf("buck_py_function");
    }
    st.add("name", type.getName());
    // Mandatory params must come before optional ones.
    st.add(
        "params",
        ImmutableList.builder().addAll(mandatory.build()).addAll(optional.build()).build());
    st.add("typePropName", TYPE_PROPERTY_NAME);
    StringWriter stringWriter = new StringWriter();
    try {
      st.write(new AutoIndentWriter(stringWriter, "\n"));
    } catch (IOException e) {
      throw new IllegalStateException("ST writer should not throw with StringWriter", e);
    }
    return stringWriter.toString();
  }

  private static final ImmutableList<String> UDR_IMPLICIT_REQUIRED_ATTRIBUTES =
      getUdrImplicits(SkylarkRuleFunctions.IMPLICIT_ATTRIBUTES, true);
  private static final ImmutableList<String> UDR_IMPLICIT_OPTIONAL_ATTRIBUTES =
      getUdrImplicits(SkylarkRuleFunctions.IMPLICIT_ATTRIBUTES, false);
  private static final ImmutableList<String> UDR_IMPLICIT_REQUIRED_TEST_ATTRIBUTES =
      getUdrImplicits(SkylarkRuleFunctions.IMPLICIT_TEST_ATTRIBUTES, true);;
  private static final ImmutableList<String> UDR_IMPLICIT_OPTIONAL_TEST_ATTRIBUTES =
      getUdrImplicits(SkylarkRuleFunctions.IMPLICIT_TEST_ATTRIBUTES, false);

  private static ImmutableList<String> getUdrImplicits(
      ImmutableMap<String, Attribute<?>> implicitAttributes, boolean requiredParams) {
    return implicitAttributes.entrySet().stream()
        .filter(e -> e.getValue().getMandatory() == requiredParams)
        .map(Map.Entry::getKey)
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * Returns a python string containing all of the default rule parameters that should be made
   * available to user defined rules in the python build file parser
   */
  public String addDefaultAttributes() {
    STGroup group = buckPyFunctionTemplate.get();

    ST st;
    // STGroup#getInstanceOf may not be thread safe.
    // See discussion in: https://github.com/antlr/stringtemplate4/issues/61
    synchronized (group) {
      st = group.getInstanceOf("buck_py_attrs");
    }

    st.add("implicit_required_attrs", UDR_IMPLICIT_REQUIRED_ATTRIBUTES);
    st.add("implicit_optional_attrs", UDR_IMPLICIT_OPTIONAL_ATTRIBUTES);
    st.add("implicit_required_test_attrs", UDR_IMPLICIT_REQUIRED_TEST_ATTRIBUTES);
    st.add("implicit_optional_test_attrs", UDR_IMPLICIT_OPTIONAL_TEST_ATTRIBUTES);

    try {
      StringWriter stringWriter = new StringWriter();
      st.write(new AutoIndentWriter(stringWriter, "\n"));
      return stringWriter.toString();
    } catch (IOException e) {
      throw new IllegalStateException("ST writer should not throw with StringWriter", e);
    }
  }

  private boolean isSkippable(ParamInfo<?> param) {
    if ("name".equals(param.getName())) {
      if (!String.class.equals(param.getResultClass())) {
        throw new HumanReadableException("'name' parameter must be a java.lang.String");
      }
      return true;
    }

    if (VisibilityAttributes.VISIBILITY.equals(param.getName())) {
      throw new HumanReadableException(
          "'visibility' parameter must be omitted. It will be passed to the rule at run time.");
    }

    if (VisibilityAttributes.WITHIN_VIEW.equals(param.getName())) {
      throw new HumanReadableException(
          "'within_view' parameter must be omitted. It will be passed to the rule at run time.");
    }

    return false;
  }

  @SuppressWarnings("unused")
  private static class StParamInfo {
    public final String name;
    public final String pythonName;
    public final boolean optional;

    public StParamInfo(ParamInfo<?> info) {
      this.name = info.getName();
      this.pythonName = info.getPythonName();
      this.optional = info.isOptional();
    }

    public static StParamInfo ofOptionalValue(String name, String pythonName) {
      return new StParamInfo(name, pythonName, true);
    }

    private StParamInfo(String name, String pythonName, boolean optional) {
      this.name = name;
      this.pythonName = pythonName;
      this.optional = optional;
    }
  }
}
