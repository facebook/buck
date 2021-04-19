package net.starlark.java.eval;

import com.google.common.base.Joiner;
import java.util.ArrayList;
import java.util.List;

/** Utility to collect missing params for {@link StarlarkFunction} and {@link BuiltinFunction}. */
class MissingParams {

  private final String name;

  private ArrayList<String> positional = new ArrayList<>();
  private ArrayList<String> named = new ArrayList<>();

  MissingParams(String name) {
    this.name = name;
  }

  void addPositional(String name) {
    positional.add(name);
  }

  void addNamed(String name) {
    named.add(name);
  }

  /** Create exception for missing params. */
  public EvalException error() {
    if (!positional.isEmpty()) {
      return Starlark.errorf(
          "%s() missing %d required positional argument%s: %s",
          name,
          positional.size(),
          StarlarkFunction.plural(positional.size()),
          Joiner.on(", ").join(positional));
    }
    if (!named.isEmpty()) {
      return Starlark.errorf(
          "%s() missing %d required keyword-only argument%s: %s",
          name,
          named.size(),
          StarlarkFunction.plural(named.size()),
          Joiner.on(", ").join(named));
    }
    throw new AssertionError("unreachable: must have at least one positional or named param");
  }
}
