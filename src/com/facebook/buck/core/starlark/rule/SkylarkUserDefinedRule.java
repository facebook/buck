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

package com.facebook.buck.core.starlark.rule;

import com.facebook.buck.core.starlark.coercer.SkylarkParamInfo;
import com.facebook.buck.core.starlark.compatible.BuckSkylarkTypes;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.AttributeHolder;
import com.facebook.buck.core.starlark.rule.names.UserDefinedRuleNames;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.SkylarkExportable;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * The {@link BaseFunction} that is returned by `rule()`. Accepts user-specified parameters, and
 * adds invocations to the parse context. Type checking is done with coercion later; it is not
 * checked in this class.
 */
public class SkylarkUserDefinedRule extends BaseFunction implements SkylarkExportable {

  private static final String TEST_RULE_SUFFIX = "_test";

  private boolean isExported = false;
  @Nullable private String name = null;
  @Nullable private Label label = null;
  @Nullable private String exportedName = null;
  private final BaseFunction implementation;
  private final ImmutableMap<String, Attribute<?>> attrs;
  private final Set<String> hiddenImplicitAttributes;
  private final boolean shouldInferRunInfo;
  private final boolean shouldBeTestRule;
  private final ImmutableMap<String, ParamInfo<?>> params;

  private SkylarkUserDefinedRule(
      FunctionSignature.WithValues<Object, SkylarkType> signature,
      Location location,
      BaseFunction implementation,
      ImmutableMap<String, Attribute<?>> attrs,
      Set<String> hiddenImplicitAttributes,
      boolean shouldInferRunInfo,
      boolean shouldBeTestRule) {
    /**
     * The name is incomplete until {@link #export(Label, String)} is called, so we know what is on
     * the left side of the assignment operator to create a function name
     */
    super("<incomplete rule>", signature, location);
    this.implementation = implementation;
    this.attrs = attrs;
    this.hiddenImplicitAttributes = hiddenImplicitAttributes;
    this.shouldInferRunInfo = shouldInferRunInfo;
    this.shouldBeTestRule = shouldBeTestRule;
    this.params =
        getAttrs().entrySet().stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    Map.Entry::getKey, e -> new SkylarkParamInfo<>(e.getKey(), e.getValue())));
  }

  @Override
  protected Object call(Object[] args, @Nullable FuncallExpression ast, Environment env)
      throws EvalException, InterruptedException {
    // We're being called directly somewhere that is not in the parser (e.g. with Location.BuiltIn)
    if (ast == null) {
      throw new EvalException(
          location, "Invalid parser state while trying to call result of rule()");
    }
    ImmutableList<String> names =
        Objects.requireNonNull(this.getSignature()).getSignature().getNames();
    Preconditions.checkArgument(
        names.size() == args.length, "Got different number of arguments than expected");
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
            .put("buck.type", this.getName());
    /**
     * We can iterate through linearly because the calling conventions of {@link BaseFunction} are
     * such that it makes an {@link Object} array with arguments in the same order as our signature
     * that is constructed in {@link #createSignature} below. If this protected method is invoked in
     * any other way, this contract is broken, and undefined behavior may result.
     */
    int i = 0;
    for (String name : names) {
      builder.put(name, args[i]);
      i++;
    }
    parseContext.recordRule(builder.build(), ast);
    return Runtime.NONE;
  }

  /**
   * Override the built-in signature printer, as it prints out '*' unconditionally if all arguments
   * are kwargs
   */
  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    String funcName = name == null ? "<incomplete function>" : getName();
    builder.append(funcName).append("(");
    Objects.requireNonNull(getSignature()).toStringBuilder(builder);
    int starIdx = builder.indexOf("*, ");
    builder.delete(starIdx, starIdx + 3);
    builder.append(")");
    return builder.toString();
  }

  /** Create an instance of {@link SkylarkUserDefinedRule} */
  public static SkylarkUserDefinedRule of(
      Location location,
      BaseFunction implementation,
      ImmutableMap<String, Attribute<?>> implicitAttributes,
      Set<String> hiddenImplicitAttributes,
      Map<String, AttributeHolder> attrs,
      boolean inferRunInfo,
      boolean test)
      throws EvalException {

    validateImplementation(location, implementation);

    ImmutableMap<String, Attribute<?>> validatedAttrs =
        validateAttrs(location, implicitAttributes, attrs);

    FunctionSignature.WithValues<Object, SkylarkType> signature =
        createSignature(validatedAttrs, location);
    return new SkylarkUserDefinedRule(
        signature,
        location,
        implementation,
        validatedAttrs,
        hiddenImplicitAttributes,
        inferRunInfo,
        test);
  }

  private static void validateImplementation(Location location, BaseFunction implementation)
      throws EvalException {
    // Make sure we only take a single (ctx) argument
    int numArgs =
        Objects.requireNonNull(implementation.getSignature())
            .getSignature()
            .getShape()
            .getArguments();
    if (numArgs != 1) {
      throw new EvalException(
          location,
          String.format(
              "Implementation function '%s' must accept a single 'ctx' argument. Accepts %s arguments",
              implementation.getName(), numArgs));
    }
  }

  private static ImmutableMap<String, Attribute<?>> validateAttrs(
      Location location,
      Map<String, Attribute<?>> implicitAttributes,
      Map<String, AttributeHolder> attrs)
      throws EvalException {
    /**
     * Make sure no one is trying to override built-in names. Ensuring that names are valid
     * identifiers happens when the {@link SkylarkUserDefinedRule} is created, in {@link
     * #createSignature(ImmutableMap, Location)}
     */
    for (String implicitAttribute : implicitAttributes.keySet()) {
      if (attrs.containsKey(implicitAttribute)) {
        throw new EvalException(
            location,
            String.format(
                "Provided attr '%s' shadows implicit attribute. Please remove it.",
                implicitAttribute));
      }
    }

    ImmutableMap<String, Attribute<?>> attrsWithoutHolder =
        attrs.entrySet().stream()
            .collect(
                ImmutableMap.toImmutableMap(Map.Entry::getKey, v -> v.getValue().getAttribute()));

    return ImmutableMap.<String, Attribute<?>>builder()
        .putAll(implicitAttributes)
        .putAll(attrsWithoutHolder)
        .build();
  }

  private static FunctionSignature.WithValues<Object, SkylarkType> createSignature(
      ImmutableMap<String, Attribute<?>> parameters, Location location) throws EvalException {
    /**
     * See {@link FunctionSignature} for details on how argument ordering works. We make all
     * arguments kwargs, so ignore the "positional" arguments
     */
    Preconditions.checkState(!parameters.isEmpty());
    int mandatory = 0;
    String[] names = new String[parameters.size()];
    ArrayList<Object> defaultValues = new ArrayList<>(parameters.size());

    Stream<Map.Entry<String, Attribute<?>>> sortedStream =
        parameters.entrySet().stream()
            .filter(e -> !e.getKey().startsWith("_"))
            .sorted(MandatoryComparator.INSTANCE);
    int i = 0;
    for (Map.Entry<String, Attribute<?>> entry :
        (Iterable<Map.Entry<String, Attribute<?>>>) sortedStream::iterator) {

      Attribute<?> param = entry.getValue();
      if (param.getMandatory()) {
        mandatory++;
      } else {
        defaultValues.add(param.getPreCoercionDefaultValue());
      }

      String name = entry.getKey();
      BuckSkylarkTypes.validateKwargName(location, name);
      names[i] = name;
      i++;
    }

    // If we filtered anything out, shrink the array
    if (i < parameters.size()) {
      names = Arrays.copyOfRange(names, 0, i);
    }

    try {
      FunctionSignature signature = FunctionSignature.of(0, 0, mandatory, false, false, names);
      return FunctionSignature.WithValues.create(signature, defaultValues, null);
    } catch (Exception e) {
      throw new EvalException(location, "Could not create FunctionSignature");
    }
  }

  /** Ensure that we only get our name after this function has been exported */
  @Override
  public String getName() {
    return Preconditions.checkNotNull(
        name, "Tried to get name before function has been assigned to a variable and exported");
  }

  /**
   * Get the string representation of the label of extension file. Only may be called after calling
   * {@link #export(Label, String)}
   *
   * @return
   */
  public Label getLabel() {
    return Preconditions.checkNotNull(
        label, "Tried to get label before function has been assigned to a variable and exported");
  }

  /**
   * Get the exported name of the function within an extension file. Only may be called after
   * calling {@link #export(Label, String)}
   */
  public String getExportedName() {
    return Preconditions.checkNotNull(
        exportedName,
        "Tried to get exported name before function has been assigned to a variable and exported");
  }

  /** Whether RunInfo should be inferred for this rule */
  public boolean shouldInferRunInfo() {
    return shouldInferRunInfo;
  }

  /** Whether this rule is expected to be a test rule or not */
  public boolean shouldBeTestRule() {
    return shouldBeTestRule;
  }

  @Override
  public boolean isExported() {
    return isExported;
  }

  @Override
  public void export(Label extensionLabel, String exportedName) throws EvalException {
    if (exportedName.endsWith(TEST_RULE_SUFFIX) && !shouldBeTestRule()) {
      throw new EvalException(
          location,
          String.format(
              "Only rules with `test = True` may end with `%s`. Got %s",
              TEST_RULE_SUFFIX, exportedName));
    }
    if (!exportedName.endsWith(TEST_RULE_SUFFIX) && shouldBeTestRule()) {
      throw new EvalException(
          location,
          String.format(
              "Rules with `test = True` must end with `%s`. Got %s",
              TEST_RULE_SUFFIX, exportedName));
    }
    this.name = UserDefinedRuleNames.getIdentifier(extensionLabel, exportedName);
    this.label = extensionLabel;
    this.exportedName = exportedName;
    this.isExported = true;
  }

  /** The attributes that this function accepts */
  public ImmutableMap<String, Attribute<?>> getAttrs() {
    return attrs;
  }

  /** The implementation function used during the analysis phase */
  BaseFunction getImplementation() {
    return implementation;
  }

  /** Get ParamInfo objects for all of the {@link Attribute}s provided to this instance */
  public ImmutableMap<String, ParamInfo<?>> getAllParamInfo() {
    return params;
  }

  public Set<String> getHiddenImplicitAttributes() {
    return hiddenImplicitAttributes;
  }

  private static class MandatoryComparator implements Comparator<Map.Entry<String, Attribute<?>>> {
    static final MandatoryComparator INSTANCE = new MandatoryComparator();

    @Override
    public int compare(
        Map.Entry<String, Attribute<?>> left, Map.Entry<String, Attribute<?>> right) {
      Attribute<?> leftAttr = left.getValue();
      Attribute<?> rightAttr = right.getValue();

      if (left.getKey().equals(right.getKey())) {
        return 0;
      }
      if ("name".equals(left.getKey())) {
        return -1;
      }
      if ("name".equals(right.getKey())) {
        return 1;
      }
      if (leftAttr.getMandatory() == rightAttr.getMandatory()) {
        return left.getKey().compareTo(right.getKey());
      }
      return leftAttr.getMandatory() ? -1 : 1;
    }
  }
}
