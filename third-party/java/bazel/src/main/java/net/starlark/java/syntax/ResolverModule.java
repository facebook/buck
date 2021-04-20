package net.starlark.java.syntax;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A Module is a static abstraction of a Starlark module (see {@link
 * net.starlark.java.eval.Module})). It describes, for the resolver and compiler, the set of
 * variable names that are predeclared, either by the interpreter (UNIVERSAL) or by the
 * application (PREDECLARED), plus the set of pre-defined global names (which is typically empty,
 * except in a REPL or EvaluationTestCase scenario).
 */
public class ResolverModule {

  // The module's global variables, in order of creation.
  private final LinkedHashMap<String, Integer> globalIndex = new LinkedHashMap<>();

  // The module's predeclared environment. Excludes UNIVERSE bindings.
  private final ImmutableMap<String, Object> predeclared;
  private final ImportedScopeObjects universe;

  private boolean frozen;

  public ResolverModule(
      ImmutableMap<String, Object> predeclared,
      ImportedScopeObjects universe) {
    this.predeclared = predeclared;
    this.universe = universe;
  }

  public static ResolverModule empty() {
    return new ResolverModule(ImmutableMap.of(), ImportedScopeObjects.EMPTY);
  }

  /** Prevent modifications. */
  public void freeze() {
    this.frozen = true;
  }

  public int globalIndexSize() {
    return globalIndex.size();
  }

  public Map<String, Integer> getIndex() {
    return globalIndex;
  }

  /**
   * Returns the index within this Module of a global variable, given its name, creating a new slot
   * for it if needed. The numbering of globals used by these functions is not the same as the
   * numbering within any compiled Program. Thus each StarlarkFunction must contain a secondary
   * index mapping Program indices (from Binding.index) to Module indices.
   */
  public int getIndexOfGlobal(String name) {
    Preconditions.checkState(!frozen, "cannot modify module when frozen");
    int i = globalIndex.size();
    Integer prev = globalIndex.putIfAbsent(name, i);
    if (prev != null) {
      return prev;
    }
    return i;
  }

  @Nullable
  public Integer getIndexOfGlobalOrNull(String name) {
    return globalIndex.get(name);
  }

  /** O(N) */
  public String getGlobalNameByIndexSlow(int index) {
    for (Map.Entry<String, Integer> e : globalIndex.entrySet()) {
      if (e.getValue() == index) {
        return e.getKey();
      }
    }
    throw new IllegalStateException("wrong global index: " + index);
  }

  /** O(N) */
  public ImmutableList<String> getGlobalNamesSlow() {
    String[] names = new String[globalIndex.size()];
    for (Map.Entry<String, Integer> e : globalIndex.entrySet()) {
      int index = e.getValue();
      Preconditions.checkState(names[index] == null);
      names[index] = e.getKey();
    }
    return ImmutableList.copyOf(names);
  }

  /**
   * Name resolve result.
   */
  public static class ResolvedName {

    final Resolver.Scope scope;
    final int nameIndex;

    private ResolvedName(Resolver.Scope scope, int nameIndex) {
      this.scope = scope;
      this.nameIndex = nameIndex;
    }

    /**
     * Binding is predeclared by the application (e.g. glob in Bazel).
     */
    public static final ResolvedName PREDECLARED = new ResolvedName(Resolver.Scope.PREDECLARED, -1);

    public static ResolvedName global(int nameIndex) {
      Preconditions.checkArgument(nameIndex >= 0);
      return new ResolvedName(Resolver.Scope.GLOBAL, nameIndex);
    }

    /**
     * Binding is predeclared by the core (e.g. None).
     */
    public static ResolvedName universal(int nameIndex) {
      Preconditions.checkArgument(nameIndex >= 0);
      return new ResolvedName(Resolver.Scope.UNIVERSAL, nameIndex);
    }
  }

  /** Returns the value of a predeclared (not universal) binding in this module. */
  @Nullable
  public Object getPredeclared(String name) {
    return predeclared.get(name);
  }

  /**
   * Returns this module's additional predeclared bindings. (Excludes universe.)
   */
  public ImmutableMap<String, Object> getPredeclaredBindings() {
    return predeclared;
  }

  /** Implements the resolver's module interface. */
  public ResolvedName resolve(String name) throws Undefined {
    // global?
    Integer globalNameIndex = globalIndex.get(name);
    if (globalNameIndex != null) {
      return ResolvedName.global(globalNameIndex);
    }

    // predeclared?
    Object v = getPredeclared(name);
    if (v != null) {
      return ResolvedName.PREDECLARED;
    }

    // universal?
    int universeIndex = universe.indexByName(name);
    if (universeIndex >= 0) {
      return ResolvedName.universal(universeIndex);
    }

    // undefined
    Set<String> candidates = new HashSet<>();
    candidates.addAll(getIndex().keySet());
    candidates.addAll(getPredeclaredBindings().keySet());
    candidates.addAll(universe.names());
    throw new Undefined(String.format("name '%s' is not defined", name), candidates);
  }

  /**
   * An Undefined exception indicates a failure to resolve a top-level name. If {@code candidates}
   * is non-null, it provides the set of accessible top-level names, which, along with local
   * names, will be used as candidates for spelling suggestions.
   */
  public static final class Undefined extends Exception {

    @Nullable
    final Set<String> candidates;

    public Undefined(String message, @Nullable Set<String> candidates) {
      super(message);
      this.candidates = candidates;
    }
  }
}
