// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.eval;

import net.starlark.java.spelling.SpellChecker;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.LoadStatement;
import net.starlark.java.syntax.Resolver;

final class Eval {

  private Eval() {} // uninstantiable

  // ---- entry point ----

  private static StarlarkFunction fn(StarlarkThread.Frame fr) {
    return (StarlarkFunction) fr.fn;
  }

  static StarlarkFunction newFunction(
      StarlarkThread thread,
      StarlarkThread.Frame fr,
      Object[] locals,
      Resolver.Function rfn,
      Tuple defaults)
      throws EvalException, InterruptedException {

    // Capture the cells of the function's
    // free variables from the lexical environment.
    Object[] freevars = new Object[rfn.getFreeVars().size()];
    int i = 0;
    for (Resolver.Binding bind : rfn.getFreeVars()) {
      // Unlike expr(Identifier), we want the cell itself, not its content.
      switch (bind.getScope()) {
        case FREE:
          freevars[i++] = fn(fr).getFreeVar(bind.getIndex());
          break;
        case CELL:
          freevars[i++] = locals[bind.getIndex()];
          break;
        default:
          throw new IllegalStateException("unexpected: " + bind);
      }
    }

    // Nested functions use the same globalIndex as their enclosing function,
    // since both were compiled from the same Program.
    StarlarkFunction fn = fn(fr);
    return new StarlarkFunction(thread, rfn, fn.getModule(), defaults, Tuple.wrap(freevars));
  }

  static void execLoad(
      StarlarkThread thread, StarlarkThread.Frame fr, Object[] locals, LoadStatement node)
      throws EvalException {
    // Has the application defined a behavior for load statements in this thread?
    StarlarkThread.Loader loader = thread.getLoader();
    if (loader == null) {
      fr.setErrorLocation(node.getStartLocation());
      throw Starlark.errorf("load statements may not be executed in this thread");
    }

    // Load module.
    String moduleName = node.getImport().getValue();
    LoadedModule module = loader.load(moduleName);
    if (module == null) {
      fr.setErrorLocation(node.getStartLocation());
      throw Starlark.errorf("module '%s' not found", moduleName);
    }

    for (LoadStatement.Binding binding : node.getBindings()) {
      // Extract symbol.
      Identifier orig = binding.getOriginalName();
      Object value = module.getGlobal(orig.getName());
      if (value == null) {
        fr.setErrorLocation(orig.getStartLocation());
        throw Starlark.errorf(
            "file '%s' does not contain symbol '%s'%s",
            moduleName,
            orig.getName(),
            SpellChecker.didYouMean(orig.getName(), module.getGlobalNamesForSpelling()));
      }

      assignIdentifier(fr, locals, binding.getLocalName(), value);
    }
  }

  private static void assignIdentifier(
      StarlarkThread.Frame fr, Object[] locals, Identifier id, Object value) throws EvalException {
    Resolver.Binding bind = id.getBinding();
    switch (bind.getScope()) {
      case LOCAL:
        locals[bind.getIndex()] = value;
        break;
      case CELL:
        ((StarlarkFunction.Cell) locals[bind.getIndex()]).x = value;
        break;
      case GLOBAL:
        fn(fr).getModule().setGlobalByIndex(bind.getIndex(), value);
        break;
      default:
        throw new IllegalStateException(bind.getScope().toString());
    }
  }

  @SuppressWarnings("unchecked")
  static Object inplaceBinaryPlus(StarlarkThread thread, Object x, Object y) throws EvalException {
    // list += iterable  behaves like  list.extend(iterable)
    // TODO(b/141263526): following Python, allow list+=iterable (but not list+iterable).
    if (x instanceof StarlarkList && y instanceof StarlarkList) {
      StarlarkList<Object> list = (StarlarkList<Object>) x;
      list.addElements((StarlarkList<Object>) y);
      return list;
    }
    return EvalUtils.binaryPlus(x, y, thread.mutability());
  }

  // ---- expressions ----

  private static Object eval(StarlarkThread.Frame fr, Expression expr)
      throws EvalException, InterruptedException {
    // We only interpret bytecode.
    throw new AssertionError("dead code");
  }
}
