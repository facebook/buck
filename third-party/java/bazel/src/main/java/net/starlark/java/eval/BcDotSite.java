package net.starlark.java.eval;

import javax.annotation.Nullable;
import net.starlark.java.spelling.SpellChecker;

/** Cache for the {@link BcInstrOpcode#DOT} instruction. */
class BcDotSite {
  private final String name;

  BcDotSite(String name) {
    this.name = name;
  }

  @Nullable private Cache cache = null;

  Object getattr(StarlarkThread thread, Object self) throws EvalException, InterruptedException {

    // This code is similar to `Starlark.getattr`, please keep it in sync.

    Cache cache = this.cache;
    if (cache == null || cache.selfClass != self.getClass()) {
      MethodDescriptor method = CallUtils.getAnnotatedMethods(self.getClass()).get(name);

      cache = this.cache = new Cache(self.getClass(), method);
    }

    if (cache.desc != null) {
      if (cache.desc.isStructField()) {
        return cache.desc.callField(self, thread.getSemantics(), thread);
      } else {
        return new BuiltinFunction(self, name, cache.desc);
      }
    }

    // user-defined field?
    if (self instanceof Structure) {
      Structure struct = (Structure) self;
      Object field = struct.getField(name);
      if (field != null) {
        return field;
      }

      String error = struct.getErrorMessageForUnknownField(name);
      if (error != null) {
        throw Starlark.errorf("%s", error);
      }
    }

    throw error(thread, self, name);
  }

  static EvalException error(StarlarkThread thread, Object self, String name) {
    return Starlark.errorf(
        "'%s' value has no field or method '%s'%s",
        Starlark.type(self),
        name,
        SpellChecker.didYouMean(name, Starlark.dir(thread.mutability(), self)));
  }

  private static class Cache {
    private final Class<?> selfClass;
    @Nullable private final MethodDescriptor desc;

    public Cache(Class<?> selfClass, @Nullable MethodDescriptor desc) {
      this.selfClass = selfClass;
      this.desc = desc;
    }
  }

  @Override
  public String toString() {
    return name;
  }
}
