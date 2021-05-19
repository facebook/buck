package net.starlark.java.eval;

import com.google.common.collect.ImmutableMap;
import java.util.Collection;
import javax.annotation.Nullable;

/** A module to be provided to load callback. */
public interface LoadedModule {

  /** Query module. */
  @Nullable
  Object getGlobal(String name);

  /** Names available in the module, used for diagnostics. */
  Collection<String> getGlobalNamesForSpelling();

  /** Simple map-based module. */
  class Simple implements LoadedModule {
    private final ImmutableMap<String, Object> module;

    public Simple(ImmutableMap<String, Object> module) {
      this.module = module;
    }

    @Nullable
    @Override
    public Object getGlobal(String name) {
      return module.get(name);
    }

    @Override
    public Collection<String> getGlobalNamesForSpelling() {
      return module.keySet();
    }
  }
}
