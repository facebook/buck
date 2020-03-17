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

import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.RuleDescriptor;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.ParamsInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import java.io.PrintStream;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;

/** Prints a requested rule type as a Python function with all supported attributes. */
public class AuditRuleTypeCommand extends AbstractCommand {

  private static final String INDENT = "    ";

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) {
    if (ruleName == null || ruleName.isEmpty()) {
      throw new CommandLineException("Must specify the rule name");
    }

    KnownRuleTypes knownRuleTypes =
        params.getKnownRuleTypesProvider().get(params.getCells().getRootCell());
    RuleDescriptor<?> descriptor = knownRuleTypes.getDescriptorByName(ruleName);
    printPythonFunction(params.getConsole(), descriptor, params.getTypeCoercerFactory());
    return ExitCode.SUCCESS;
  }

  @Argument @Nullable private String ruleName;

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "Print requested build rule type as a Python function signature.";
  }

  static void printPythonFunction(
      Console console, RuleDescriptor<?> descriptor, TypeCoercerFactory typeCoercerFactory) {
    PrintStream printStream = console.getStdOut();
    ParamsInfo allParamInfo =
        descriptor.getDtoDescriptor().apply(typeCoercerFactory).getParamsInfo();
    String name = descriptor.getRuleType().getName();
    printStream.println("def " + name + " (");
    allParamInfo.getParamInfosSorted().stream()
        .filter(param -> !param.isOptional())
        .forEach(formatPythonFunction(printStream));
    allParamInfo.getParamInfosSorted().stream()
        .filter(ParamInfo::isOptional)
        .forEach(formatPythonFunction(printStream));
    printStream.println("):");
    printStream.println(INDENT + "...");
  }

  private static Consumer<ParamInfo<?>> formatPythonFunction(PrintStream printStream) {
    return param ->
        printStream.println(
            INDENT + param.getPythonName() + (param.isOptional() ? " = None" : "") + ",");
  }
}
