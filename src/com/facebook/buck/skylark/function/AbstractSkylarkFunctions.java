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

import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.skylark.parser.context.ReadConfigContext;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.packages.SelectorList;
import com.google.devtools.build.lib.packages.SelectorValue;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.Dict;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkCallable;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.syntax.Tuple;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkMethod;

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
  @StarlarkMethod(
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
      useStarlarkThread = true)
  public Object readConfig(String section, String field, Object defaultValue, StarlarkThread env)
      throws EvalException {
    ReadConfigContext configContext = ReadConfigContext.getContext(env);
    @Nullable
    String value = configContext.getRawConfig().getOrDefault(section, ImmutableMap.of()).get(field);

    configContext.recordReadConfigurationOption(section, field, value);
    return value != null ? value : defaultValue;
  }

  /** {@code sha256} */
  @StarlarkMethod(
      name = "sha256",
      doc = "Computes a sha256 digest for a string. Returns the hex representation of the digest.",
      parameters = {@Param(name = "value", type = String.class, named = true)})
  public String sha256(String value) {
    return Hashing.sha256().hashString(value, StandardCharsets.UTF_8).toString();
  }

  /** {@code load_symbols} */
  @StarlarkMethod(
      name = "load_symbols",
      doc = "Loads symbols into the current build context.",
      parameters = {@Param(name = "symbols", type = Dict.class, named = true)},
      useStarlarkThread = true)
  public void loadSymbols(Dict<?, ?> symbols /* <String, Any> */, StarlarkThread env) {
    LoadSymbolsContext loadSymbolsContext = env.getThreadLocal(LoadSymbolsContext.class);
    if (loadSymbolsContext == null) {
      throw new BuckUncheckedExecutionException(
          "%s is not specified", LoadSymbolsContext.class.getSimpleName());
    }
    for (Object keyObj : symbols) {
      if (keyObj instanceof String) {
        String key = (String) keyObj;
        loadSymbolsContext.putSymbol(key, symbols.get(keyObj));
      }
    }
  }

  /** {@code partial} */
  @StarlarkMethod(
      name = "partial",
      doc =
          "new function with partial application of the given arguments and keywords. "
              + "Roughly equivalent to functools.partial.",
      parameters = {@Param(name = "func", type = StarlarkCallable.class)},
      extraPositionals = @Param(name = "args"),
      extraKeywords = @Param(name = "kwargs"))
  public BaseFunction partial(
      StarlarkCallable func, Tuple<Object> args, Dict<String, Object> kwargs) {
    return new BaseFunction() {
      @Override
      public Object call(
          StarlarkThread thread, Tuple<Object> inner_args, Dict<String, Object> inner_kwargs)
          throws EvalException, InterruptedException {
        // Sadly, neither Dict.plus() nor MethodLibrary.dict() are accessible.
        Dict<String, Object> merged_args = Dict.copyOf(thread.mutability(), kwargs);
        merged_args.update(inner_kwargs, Dict.empty(), thread);
        return Starlark.call(thread, func, Tuple.concat(args, inner_args), merged_args);
      }

      @Override
      public String getName() {
        return "<partial>";
      }

      @Override
      public FunctionSignature getSignature() {
        return FunctionSignature.ANY;
      }
    };
  }

  /**
   * Returns a function-value implementing "select" (i.e. configurable attributes) in the specified
   * package context.
   */
  @StarlarkMethod(
      name = "select",
      doc =
          "<code>select()</code> is the helper function that makes a rule attribute "
              + "<a href=\"$BE_ROOT/common-definitions.html#configurable-attributes\">"
              + "configurable</a>. See "
              + "<a href=\"$BE_ROOT/functions.html#select\">build encyclopedia</a> for details.",
      parameters = {
        @Param(name = "x", type = Dict.class, doc = "The parameter to convert."),
        @Param(
            name = "no_match_error",
            type = String.class,
            defaultValue = "''",
            doc = "Optional custom error to report if no condition matches.",
            named = true)
      })
  public Object select(Dict<?, ?> dict, String noMatchError) throws EvalException {
    if (dict.isEmpty()) {
      throw Starlark.errorf(
          "select({}) with an empty dictionary can never resolve because it includes no conditions"
              + " to match");
    }
    for (Object key : dict.keySet()) {
      if (!(key instanceof String)) {
        throw Starlark.errorf("Invalid key: %s. select keys must be label references", key);
      }
    }
    // TODO(nga): use our version of selectors
    return SelectorList.of(new SelectorValue(dict, noMatchError));
  }

  /** {@code selectEqualInternal} */
  @StarlarkMethod(
      name = "select_equal_internal",
      doc = "Test Only. Check equality between two select expressions",
      parameters = {
        @Param(name = "first", type = SelectorList.class),
        @Param(name = "other", type = SelectorList.class),
      },
      documented = false)
  public boolean selectEqualInternal(SelectorList first, SelectorList other) {
    if (!(Objects.equals(first.getType(), other.getType()))) {
      return false;
    }

    Iterator<Object> it1 = first.getElements().iterator();
    Iterator<Object> it2 = other.getElements().iterator();

    while (it1.hasNext() && it2.hasNext()) {
      Object o1 = it1.next();
      Object o2 = it2.next();

      if (o1 == o2) {
        continue;
      }

      if (!o1.getClass().equals(o2.getClass())) {
        return false;
      }

      if (o1 instanceof SelectorValue && o2 instanceof SelectorValue) {
        SelectorValue s1 = (SelectorValue) o1;
        SelectorValue s2 = (SelectorValue) o2;

        if (!Objects.equals(s1.getDictionary(), s2.getDictionary())) {
          return false;
        }

      } else {
        if (!Objects.equals(o1, o2)) {
          return false;
        }
      }
    }

    return !(it1.hasNext() || it2.hasNext());
  }

  /**
   * Exposes a {@code select_map} for Skylark parser.
   *
   * <p>This allows users to introspect and manipulate values in a select expression. The mapping
   * function operates on each individual value in the select expression, and generates a new select
   * expression with the updated values.
   */
  @StarlarkMethod(
      name = "select_map",
      doc = "Iterate over and modify a select expression using the map function",
      parameters = {
        @Param(name = "selector_list", type = SelectorList.class),
        @Param(name = "func", type = StarlarkCallable.class)
      },
      useStarlarkThread = true)
  public SelectorList select_map(
      SelectorList selectorList, StarlarkCallable func, StarlarkThread thread)
      throws EvalException, InterruptedException {
    List<Object> new_elements = new ArrayList<>();
    for (Object element : selectorList.getElements()) {
      if (element instanceof SelectorValue) {
        SelectorValue sval = (SelectorValue) element;
        Map<Object, Object> dictionary = new HashMap<>();

        for (Map.Entry<?, ?> entry : sval.getDictionary().entrySet()) {
          dictionary.put(
              entry.getKey(),
              Starlark.call(thread, func, Arrays.asList(entry.getValue()), ImmutableMap.of()));
        }

        new_elements.add(new SelectorValue(dictionary, sval.getNoMatchError()));
      } else {
        new_elements.add(Starlark.call(thread, func, Arrays.asList(element), ImmutableMap.of()));
      }
    }
    return SelectorList.of(new_elements);
  }

  /**
   * Exposes a {@code select_test} for Skylark parser.
   *
   * <p>This API allows testing values in a select expression. The API returns true if any value in
   * the select expression passes the given test function.
   */
  @StarlarkMethod(
      name = "select_test",
      doc = "Test values in the select expression using the given function",
      parameters = {
        @Param(name = "selector_list", type = SelectorList.class),
        @Param(name = "func", type = StarlarkCallable.class)
      },
      useStarlarkThread = true)
  public boolean select_test(
      SelectorList selectorList, StarlarkCallable func, StarlarkThread thread)
      throws EvalException, InterruptedException {
    for (Object element : selectorList.getElements()) {
      if (element instanceof SelectorValue) {
        SelectorValue sval = (SelectorValue) element;

        for (Map.Entry<?, ?> entry : sval.getDictionary().entrySet()) {
          Boolean result =
              (Boolean)
                  Starlark.call(thread, func, Arrays.asList(entry.getValue()), ImmutableMap.of());
          if (result) {
            return true;
          }
        }

      } else {
        Boolean result =
            (Boolean) Starlark.call(thread, func, Arrays.asList(element), ImmutableMap.of());
        if (result) {
          return true;
        }
      }
    }
    return false;
  }
}
