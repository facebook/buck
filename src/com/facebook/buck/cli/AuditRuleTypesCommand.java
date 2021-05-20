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

package com.facebook.buck.cli;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.rules.coercer.DataTransferObjectDescriptor;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.ParamsInfo;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.kohsuke.args4j.Option;

public class AuditRuleTypesCommand extends AbstractCommand {

  @Option(name = "--json", usage = "Output in JSON format")
  private boolean generateJsonOutput;

  @Option(name = "--specs", usage = "Output in JSON format")
  private boolean generateSpecs;

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    if (generateSpecs) {
      collectAndDumpBuildRuleSpecsInformation(
          params.getConsole(),
          params.getKnownRuleTypesProvider().getNativeRuleTypes(params.getCells().getRootCell()),
          generateJsonOutput);
    } else {
      collectAndDumpBuildRuleTypesInformation(
          params.getConsole(),
          params.getKnownRuleTypesProvider().getNativeRuleTypes(params.getCells().getRootCell()),
          generateJsonOutput);
    }
    return ExitCode.SUCCESS;
  }

  private void collectAndDumpBuildRuleSpecsInformation(
      Console console, KnownNativeRuleTypes knownNativeRuleTypes, boolean generateJsonOutput)
      throws IOException {
    Set<Class<? extends Enum<?>>> encounteredEnums =
        new TreeSet<>(Comparator.comparing(Class::toString));
    // Rules should be sorted by name.
    Map<String, Map<String, String>> specs = new TreeMap<>();
    DefaultTypeCoercerFactory coercerFactory = new DefaultTypeCoercerFactory();

    for (BaseDescription<?> description : knownNativeRuleTypes.getDescriptions()) {
      DataTransferObjectDescriptor<?> descriptor =
          coercerFactory.getNativeConstructorArgDescriptor(description.getConstructorArgType());
      ParamsInfo paramsInfo = descriptor.getParamsInfo();
      // Attrs should preserve the sorting in the definition.
      Map<String, String> attrs = new LinkedHashMap<>();
      for (String attrName : paramsInfo.getParamStarlarkNames()) {
        ParamInfo<?> paramInfo = paramsInfo.getByStarlarkName(attrName);
        TypeCoercer.SkylarkSpec skylarkSpec = paramInfo.getTypeCoercer().getSkylarkSpec();

        attrs.put(attrName, skylarkSpec.topLevelSpec());
        encounteredEnums.addAll(skylarkSpec.enums());
      }
      String name = DescriptionCache.getRuleType(description).getName();
      attrs.put("visibility", "attr.option(attr.list(attr.string()))");
      attrs.put("within_view", "attr.option(attr.list(attr.string()))");
      specs.put(name, attrs);
    }

    // Sort enums by name.
    Map<String, List<String>> enums = new TreeMap<>();

    for (Class<? extends Enum<?>> e : encounteredEnums) {
      enums.put(
          e.getSimpleName(),
          Arrays.stream(e.getEnumConstants())
              .map(Enum::name)
              .map(String::toLowerCase)
              .collect(ImmutableList.toImmutableList()));
    }

    // This should be in the order we are creating it here.
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rules", specs);
    data.put("enums", enums);

    SpecPrinter printer = generateJsonOutput ? jsonSpecPrinter(console) : rawSpecPrinter();

    printer.printSpecs(data);
  }

  private SpecPrinter rawSpecPrinter() {
    return specs -> {
      throw new UnsupportedOperationException("--specs currently requires --json");
    };
  }

  private SpecPrinter jsonSpecPrinter(Console console) {
    return specs -> {
      StringWriter stringWriter = new StringWriter();
      ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, specs);
      console.getStdOut().println(stringWriter.getBuffer().toString());
    };
  }

  interface SpecPrinter {

    void printSpecs(Map<String, Object> specs) throws IOException;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "List all known build rule types";
  }

  static void collectAndDumpBuildRuleTypesInformation(
      Console console, KnownNativeRuleTypes knownNativeRuleTypes, boolean generateJsonOutput)
      throws IOException {
    Collection<String> buildRuleTypes = collectBuildRuleTypes(knownNativeRuleTypes);

    if (generateJsonOutput) {
      dumpBuildRuleTypesInJsonFormat(console, buildRuleTypes);
    } else {
      dumpBuildRuleTypesInRawFormat(console, buildRuleTypes);
    }
  }

  private static Collection<String> collectBuildRuleTypes(
      KnownNativeRuleTypes knownNativeRuleTypes) {
    return ImmutableSortedSet.copyOf(knownNativeRuleTypes.getNativeTypesByName().keySet());
  }

  private static void dumpBuildRuleTypesInJsonFormat(
      Console console, Collection<String> buildRuleTypes) throws IOException {
    StringWriter stringWriter = new StringWriter();
    ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, buildRuleTypes);
    console.getStdOut().println(stringWriter.getBuffer().toString());
  }

  private static void dumpBuildRuleTypesInRawFormat(
      Console console, Collection<String> buildRuleTypes) {
    PrintStream printStream = console.getStdOut();
    buildRuleTypes.forEach(printStream::println);
  }
}
