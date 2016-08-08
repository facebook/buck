/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

public class ReplCommand extends AbstractCommand {

  private static final String EXIT_MESSAGE = "EXITMESSAGE";

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (!params.getConsole().getAnsi().isAnsiTerminal()) {
      params.getBuckEventBus().post(ConsoleEvent.severe("This is intended for interactive use."));
      return 1;
    }

    return runInterpreter(params);
  }

  public static int runInterpreter(CommandRunnerParams params)
      throws IOException, InterruptedException {
    if (!isNashornAvailable()) {
      params.getBuckEventBus().post(ConsoleEvent.severe("This feature requires Nashorn " +
          "JavaScript engine. This comes bundled with Java 8."));
      return 0;
    }

    try (CommandThreadManager queryPool =
             new CommandThreadManager("Query", new ConcurrencyLimit(1, Float.POSITIVE_INFINITY));
         PerBuildState parserState =
             new PerBuildState(
                 params.getParser(),
                 params.getBuckEventBus(),
                 queryPool.getExecutor(),
                 params.getCell(),
                 /* enableProfiling */ false,
                 SpeculativeParsing.of(true),
                 /* ignoreBuckAutodepsFiles */ false)) {
      ScriptEngine engine = initializeEngine(params, queryPool, parserState);
      BufferedReader bufferRead = new BufferedReader(new InputStreamReader(params.getStdIn()));
      String line;
      while (true) {
        params.getConsole().getStdOut().print("buck $ ");
        try {
          if ((line = bufferRead.readLine()) != null) {
            engine.eval(line);
          } else {
            break;
          }
        } catch (ScriptException e) {
          if (!e.getMessage().contains(EXIT_MESSAGE)) {
            params.getConsole().getStdOut().println("Script error: " + e.getMessage());
          } else {
            break;
          }
        } catch (Exception e) {
          params.getConsole().getStdOut().println(e.getMessage());
        }
      }

      bufferRead.close();
    } catch (BuildFileParseException e) {
      params.getConsole().getStdOut().println("Repl failed: " + e.getMessage());
    }
    return 0;
  }

  private static ScriptEngine initializeEngine(
      CommandRunnerParams params,
      CommandThreadManager queryPool,
      PerBuildState parserState) {
    ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
    final Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);

    // Delete built-in methods of Nashorn that call System.exit()
    bindings.remove("quit");
    bindings.remove("exit");

    // Create new exit(), quit() methods that use the ScriptException message.
    String quitExitMethod = "function quit() {" +
        "var Exception = Java.type(\"java.lang.Exception\");" +
        "var ExceptionAdapter = Java.extend(Exception);" +
        "var quitException = new ExceptionAdapter(\"" + EXIT_MESSAGE + "\") { " +
        "getMessage: function() { " +
        "var _super_ = Java.super(quitException); return _super_.getMessage(); } };" +
        "throw quitException; } function exit() { quit(); }";

    // Add a help function.
    String helpMethod = "function help() { print(" +
        "\"Available commands:\\n" +
        "\\tquery(String query) -- query like buck query\\n" +
        "\\tprintQuery(query result) -- pretty print query\\n" +
        "\\texit(), quit() -- exit repl\\n" +
        "\"); }";

    // Enable query functionality.
    BuckQueryEnvironment queryEnv = new BuckQueryEnvironment(params, parserState, false);

    bindings.put("BUCK_params", params);
    bindings.put("BUCK_queryEnv", queryEnv);
    bindings.put("BUCK_queryPool", queryPool);
    bindings.put("BUCK_queryPoolExecutor", queryPool.getExecutor());

    String queryMethod = "function query(queryString) {" +
        "return BUCK_queryEnv.evaluateQuery(queryString, BUCK_queryPoolExecutor);" +
        "}";

    String printQueryMethod = "function printQuery(queryResult) {" +
        "com.facebook.buck.cli.CommandHelper.printToConsole(BUCK_params, x); }";
    try {
      engine.eval(quitExitMethod + helpMethod + queryMethod + printQueryMethod);
    } catch (ScriptException e) {
      params.getConsole().getStdOut().println("Repl initialization failed: " + e.getMessage());
    }

    params.getConsole().getStdOut().println("Welcome to buck repl.\nThis is intended for " +
        "experimentation and debugging, any of the APIs may change without notice!\nYou can exit " +
        "with exit() or quit(). Help is available with help().");

    return engine;
  }

  public static boolean isNashornAvailable() {
    try {
      Class.forName("jdk.nashorn.api.scripting.NashornScriptEngine");
    } catch (ClassNotFoundException e) {
      return false;
    }
    return true;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "a shell for interactive experimentation with buck internals";
  }
}
