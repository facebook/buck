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

import com.facebook.buck.core.model.label.Label;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.starlark.coercer.SkylarkParamInfo;
import com.facebook.buck.core.starlark.compatible.StarlarkExportable;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.AttributeHolder;
import com.facebook.buck.core.starlark.rule.names.UserDefinedRuleNames;
import com.facebook.buck.rules.coercer.ParamsInfo;
import com.facebook.buck.rules.param.CommonParamNames;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.facebook.buck.skylark.parser.context.RecordedRule;
import com.facebook.buck.skylark.parser.pojoizer.BuildFileManifestPojoizer;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;
import net.starlark.java.syntax.Location;

/**
 * The {@link StarlarkCallable} that is returned by `rule()`. Accepts user-specified parameters, and
 * adds invocations to the parse context. Type checking is done with coercion later; it is not
 * checked in this class.
 */
public class SkylarkUserDefinedRule implements StarlarkCallable, StarlarkExportable {

  private static final String TEST_RULE_SUFFIX = "_test";

  private boolean isExported = false;
  @Nullable private String name = null;
  @Nullable private Label label = null;
  @Nullable private String exportedName = null;
  @VisibleForTesting final FunctionSignature signature;
  private final Tuple defaultValues;
  private final Location location;
  private final StarlarkCallable implementation;
  private final ImmutableMap<ParamName, Attribute<?>> attrs;
  private final Set<ParamName> hiddenImplicitAttributes;
  private final boolean shouldInferRunInfo;
  private final boolean shouldBeTestRule;
  private final ParamsInfo params;

  private SkylarkUserDefinedRule(
      FunctionSignature signature,
      ImmutableList<Object> defaultValues,
      Location location,
      StarlarkCallable implementation,
      ImmutableMap<ParamName, Attribute<?>> attrs,
      Set<ParamName> hiddenImplicitAttributes,
      boolean shouldInferRunInfo,
      boolean shouldBeTestRule) {
    Preconditions.checkArgument(defaultValues.size() == signature.numOptionals());

    /**
     * The name is incomplete until {@link #export(Label, String)} is called, so we know what is on
     * the left side of the assignment operator to create a function name
     */
    this.signature = signature;
    this.defaultValues = Tuple.copyOf(defaultValues);
    this.location = location;
    this.implementation = implementation;
    this.attrs = attrs;
    this.hiddenImplicitAttributes = hiddenImplicitAttributes;
    this.shouldInferRunInfo = shouldInferRunInfo;
    this.shouldBeTestRule = shouldBeTestRule;
    this.params =
        ParamsInfo.of(
            getAttrs().entrySet().stream()
                .map(e -> new SkylarkParamInfo<>(e.getKey(), e.getValue()))
                .collect(ImmutableList.toImmutableList()));
  }

  @SuppressWarnings("unchecked")
  @Override
  public Object fastcall(StarlarkThread thread, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {

    Object[] args =
        matchSignaturePrivate(
            signature, this, defaultValues, thread.mutability(), positional, named);

    // We're being called directly somewhere that is not in the parser (e.g. with Location.BuiltIn)
    ImmutableList<String> names = Objects.requireNonNull(signature).getParameterNames();

    ParseContext parseContext = ParseContext.getParseContext(thread, getName());
    ForwardRelativePath basePath = parseContext.getPackageContext().getBasePath();
    ImmutableList<String> visibility = ImmutableList.of();
    ImmutableList<String> withinView = ImmutableList.of();
    TwoArraysImmutableHashMap.Builder<ParamName, Object> builder =
        TwoArraysImmutableHashMap.builder();

    /**
     * We can iterate through linearly because the calling conventions of {@link
     * Starlark#matchSignature(FunctionSignature, StarlarkCallable, Tuple, Mutability, Object[],
     * Object[])} are such that it makes an {@link Object} array with arguments in the same order as
     * our signature that is constructed in {@link #createSignature} below.
     */
    int i = 0;
    for (String name : names) {
      Object value = args[i];
      // sanity check
      Preconditions.checkNotNull(value);

      if (name.equals(CommonParamNames.VISIBILITY.getSnakeCase())) {
        visibility = (ImmutableList<String>) value;
      } else if (name.equals(CommonParamNames.WITHIN_VIEW.getSnakeCase())) {
        withinView = (ImmutableList<String>) value;
      } else {
        Object converted = BuildFileManifestPojoizer.convertToPojo(value);
        if (converted != Starlark.NONE) {
          builder.put(ParamName.bySnakeCase(name), converted);
        }
      }
      i++;
    }
    parseContext.recordRule(
        RecordedRule.of(basePath, this.getName(), visibility, withinView, builder.build()));
    return Starlark.NONE;
  }

  private static Object[] matchSignaturePrivate(
      FunctionSignature signature,
      StarlarkCallable func, // only for use in error messages
      Tuple defaults,
      @Nullable Mutability mu,
      Object[] positional,
      Object[] named)
      throws EvalException {
    // TODO(adonovan): simplify this function. Combine cases 1 and 2 without loss of efficiency.
    // TODO(adonovan): reduce the verbosity of errors. Printing func.toString is often excessive.
    // Make the error messages consistent in form.
    // TODO(adonovan): report an error if there were missing values in 'defaults'.

    Object[] arguments = new Object[signature.numParameters()];

    ImmutableList<String> names = signature.getParameterNames();

    // Note that this variable will be adjusted down if there are extra positionals,
    // after these extra positionals are dumped into starParam.
    int numPositionalArgs = positional.length;

    int numMandatoryPositionalParams = signature.numMandatoryPositionals();
    int numOptionalPositionalParams = signature.numOptionalPositionals();
    int numMandatoryNamedOnlyParams = signature.numMandatoryNamedOnly();
    int numOptionalNamedOnlyParams = signature.numOptionalNamedOnly();
    boolean hasVarargs = signature.hasVarargs();
    boolean hasKwargs = signature.hasKwargs();
    int numPositionalParams = numMandatoryPositionalParams + numOptionalPositionalParams;
    int numNamedOnlyParams = numMandatoryNamedOnlyParams + numOptionalNamedOnlyParams;
    int numNamedParams = numPositionalParams + numNamedOnlyParams;
    int kwargIndex = names.size() - 1; // only valid if hasKwargs

    // (1) handle positional arguments
    if (hasVarargs) {
      // Nota Bene: we collect extra positional arguments in a (tuple,) rather than a [list],
      // and this is actually the same as in Python.
      Object varargs;
      if (numPositionalArgs > numPositionalParams) {
        varargs = Tuple.of(Arrays.copyOfRange(positional, numPositionalParams, numPositionalArgs));
        numPositionalArgs = numPositionalParams; // clip numPositionalArgs
      } else {
        varargs = Tuple.empty();
      }
      arguments[numNamedParams] = varargs;
    } else if (numPositionalArgs > numPositionalParams) {
      throw new EvalException(
          null,
          numPositionalParams > 0
              ? "too many (" + numPositionalArgs + ") positional arguments in call to " + func
              : func + " does not accept positional arguments, but got " + numPositionalArgs);
    }

    if (numPositionalArgs >= 0) {
      System.arraycopy(positional, 0, arguments, 0, numPositionalArgs);
    }

    // (2) handle keyword arguments
    if (named.length == 0) {
      // Easy case (2a): there are no keyword arguments.
      // All arguments were positional, so check we had enough to fill all mandatory positionals.
      if (numPositionalArgs < numMandatoryPositionalParams) {
        throw Starlark.errorf(
            "insufficient arguments received by %s (got %s, expected at least %s)",
            func, numPositionalArgs, numMandatoryPositionalParams);
      }
      // We had no named argument, so fail if there were mandatory named-only parameters
      if (numMandatoryNamedOnlyParams > 0) {
        throw Starlark.errorf("missing mandatory keyword arguments in call to %s", func);
      }
      // Fill in defaults for missing optional parameters, that were conveniently grouped together,
      // thanks to the absence of mandatory named-only parameters as checked above.
      if (defaults != null) {
        int endOptionalParams = numPositionalParams + numOptionalNamedOnlyParams;
        for (int i = numPositionalArgs; i < endOptionalParams; i++) {
          arguments[i] = defaults.get(i - numMandatoryPositionalParams);
        }
      }
      // If there's a kwarg, it's empty.
      if (hasKwargs) {
        arguments[kwargIndex] = Dict.of(mu);
      }
    } else {
      // general case (2b): some keyword arguments may correspond to named parameters
      Dict<String, Object> kwargs = hasKwargs ? Dict.of(mu) : null;

      // Accept the named arguments that were passed.
      for (int i = 0; i < named.length; i += 2) {
        String keyword = (String) named[i]; // safe
        Object value = named[i + 1];
        int pos = names.indexOf(keyword); // the list should be short, so linear scan is OK.
        if (0 <= pos && pos < numNamedParams) {
          if (arguments[pos] != null) {
            throw Starlark.errorf("%s got multiple values for parameter '%s'", func, keyword);
          }
          arguments[pos] = value;
        } else {
          if (!hasKwargs) {
            Set<String> unexpected = Sets.newHashSet();
            for (int j = 0; j < named.length; j += 2) {
              unexpected.add((String) named[j]);
            }
            unexpected.removeAll(names.subList(0, numNamedParams));
            // TODO(adonovan): do spelling check.
            throw Starlark.errorf(
                "unexpected keyword%s '%s' in call to %s",
                unexpected.size() > 1 ? "s" : "",
                Joiner.on("', '").join(Ordering.natural().sortedCopy(unexpected)),
                func);
          }
          int sz = kwargs.size();
          kwargs.putEntry(keyword, value);
          if (kwargs.size() == sz) {
            throw Starlark.errorf(
                "%s got multiple values for keyword argument '%s'", func, keyword);
          }
        }
      }
      if (hasKwargs) {
        arguments[kwargIndex] = kwargs;
      }

      // Check that all mandatory parameters were filled in general case 2b.
      // Note: it's possible that numPositionalArgs > numMandatoryPositionalParams but that's OK.
      for (int i = numPositionalArgs; i < numMandatoryPositionalParams; i++) {
        if (arguments[i] == null) {
          throw Starlark.errorf(
              "missing mandatory positional argument '%s' while calling %s", names.get(i), func);
        }
      }

      int endMandatoryNamedOnlyParams = numPositionalParams + numMandatoryNamedOnlyParams;
      for (int i = numPositionalParams; i < endMandatoryNamedOnlyParams; i++) {
        if (arguments[i] == null) {
          throw Starlark.errorf(
              "missing mandatory named-only argument '%s' while calling %s", names.get(i), func);
        }
      }

      // Get defaults for those parameters that weren't passed.
      if (defaults != null) {
        for (int i = Math.max(numPositionalArgs, numMandatoryPositionalParams);
            i < numPositionalParams;
            i++) {
          if (arguments[i] == null) {
            arguments[i] = defaults.get(i - numMandatoryPositionalParams);
          }
        }
        int numMandatoryParams = numMandatoryPositionalParams + numMandatoryNamedOnlyParams;
        for (int i = numMandatoryParams + numOptionalPositionalParams; i < numNamedParams; i++) {
          if (arguments[i] == null) {
            arguments[i] = defaults.get(i - numMandatoryParams);
          }
        }
      }
    } // End of general case 2b for argument passing.

    return arguments;
  }

  /** Create an instance of {@link SkylarkUserDefinedRule} */
  public static SkylarkUserDefinedRule of(
      Location location,
      StarlarkCallable implementation,
      ImmutableMap<ParamName, Attribute<?>> implicitAttributes,
      Set<ParamName> hiddenImplicitAttributes,
      Map<ParamName, AttributeHolder> attrs,
      boolean inferRunInfo,
      boolean test)
      throws EvalException {

    validateImplementation(location, implementation);

    ImmutableMap<ParamName, Attribute<?>> validatedAttrs =
        validateAttrs(location, implicitAttributes, attrs);

    Pair<FunctionSignature, ImmutableList<Object>> signature =
        createSignature(validatedAttrs, location);
    return new SkylarkUserDefinedRule(
        signature.getFirst(),
        signature.getSecond(),
        location,
        implementation,
        validatedAttrs,
        hiddenImplicitAttributes,
        inferRunInfo,
        test);
  }

  /** Function with a signature, used only in tests. */
  interface BaseFunction extends StarlarkCallable {
    FunctionSignature getSignature();
  }

  private static void validateImplementation(Location location, StarlarkCallable implementation)
      throws EvalException {
    int numArgs;
    if (implementation instanceof StarlarkFunction) {
      numArgs = ((StarlarkFunction) implementation).getParameterNames().size();
    } else if (implementation instanceof BaseFunction) {
      numArgs = ((BaseFunction) implementation).getSignature().numParameters();
    } else {
      // unknown callable, cannot validate
      return;
    }
    // Make sure we only take a single (ctx) argument
    if (numArgs != 1) {
      throw new EvalException(
          location,
          String.format(
              "Implementation function '%s' must accept a single 'ctx' argument. Accepts %s arguments",
              implementation.getName(), numArgs));
    }
  }

  private static ImmutableMap<ParamName, Attribute<?>> validateAttrs(
      Location location,
      Map<ParamName, Attribute<?>> implicitAttributes,
      Map<ParamName, AttributeHolder> attrs)
      throws EvalException {
    /**
     * Make sure no one is trying to override built-in names. Ensuring that names are valid
     * identifiers happens when the {@link SkylarkUserDefinedRule} is created, in {@link
     * #createSignature(ImmutableMap, Location)}
     */
    for (ParamName implicitAttribute : implicitAttributes.keySet()) {
      if (attrs.containsKey(implicitAttribute)) {
        throw new EvalException(
            location,
            String.format(
                "Provided attr '%s' shadows implicit attribute. Please remove it.",
                implicitAttribute));
      }
    }

    ImmutableMap<ParamName, Attribute<?>> attrsWithoutHolder =
        attrs.entrySet().stream()
            .collect(
                ImmutableMap.toImmutableMap(Map.Entry::getKey, v -> v.getValue().getAttribute()));

    return ImmutableMap.<ParamName, Attribute<?>>builder()
        .putAll(implicitAttributes)
        .putAll(attrsWithoutHolder)
        .build();
  }

  private static Pair<FunctionSignature, ImmutableList<Object>> createSignature(
      ImmutableMap<ParamName, Attribute<?>> parameters, Location location) throws EvalException {
    /**
     * See {@link FunctionSignature} for details on how argument ordering works. We make all
     * arguments kwargs, so ignore the "positional" arguments
     */
    Preconditions.checkState(!parameters.isEmpty());
    int mandatory = 0;
    String[] names = new String[parameters.size()];
    ImmutableList.Builder<Object> defaultValues =
        ImmutableList.builderWithExpectedSize(parameters.size());

    Stream<Map.Entry<ParamName, Attribute<?>>> sortedStream =
        parameters.entrySet().stream()
            .filter(e -> !e.getKey().getSnakeCase().startsWith("_"))
            .sorted(MandatoryComparator.INSTANCE);
    int i = 0;
    for (Map.Entry<ParamName, Attribute<?>> entry :
        (Iterable<Map.Entry<ParamName, Attribute<?>>>) sortedStream::iterator) {

      Attribute<?> param = entry.getValue();
      if (param.getMandatory()) {
        mandatory++;
      } else {
        defaultValues.add(param.getPreCoercionDefaultValue());
      }

      ParamName name = entry.getKey();
      names[i] = name.getSnakeCase();
      i++;
    }

    // If we filtered anything out, shrink the array
    if (i < parameters.size()) {
      names = Arrays.copyOfRange(names, 0, i);
    }

    try {
      return new Pair<>(
          FunctionSignature.create(
              0, 0, mandatory, names.length - mandatory, false, false, ImmutableList.copyOf(names)),
          defaultValues.build());
    } catch (Exception e) {
      throw new EvalException(location, "Could not create FunctionSignature", e);
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
    Preconditions.checkState(!isExported);
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
  public ImmutableMap<ParamName, Attribute<?>> getAttrs() {
    return attrs;
  }

  /** The implementation function used during the analysis phase */
  StarlarkCallable getImplementation() {
    return implementation;
  }

  /** Get ParamInfo objects for all of the {@link Attribute}s provided to this instance */
  public ParamsInfo getParamsInfo() {
    return params;
  }

  public Set<ParamName> getHiddenImplicitAttributes() {
    return hiddenImplicitAttributes;
  }

  private static class MandatoryComparator
      implements Comparator<Map.Entry<ParamName, Attribute<?>>> {
    static final MandatoryComparator INSTANCE = new MandatoryComparator();

    @Override
    public int compare(
        Map.Entry<ParamName, Attribute<?>> left, Map.Entry<ParamName, Attribute<?>> right) {
      Attribute<?> leftAttr = left.getValue();
      Attribute<?> rightAttr = right.getValue();

      if (left.getKey().equals(right.getKey())) {
        return 0;
      }
      if ("name".equals(left.getKey().getSnakeCase())) {
        return -1;
      }
      if ("name".equals(right.getKey().getSnakeCase())) {
        return 1;
      }
      if (leftAttr.getMandatory() == rightAttr.getMandatory()) {
        return left.getKey().compareTo(right.getKey());
      }
      return leftAttr.getMandatory() ? -1 : 1;
    }
  }
}
