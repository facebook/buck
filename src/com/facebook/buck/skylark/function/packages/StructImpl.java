/*
 * Portions Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.skylark.function.packages;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.HasBinary;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.Structure;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.TokenKind;

/** {@code struct()} implementation for Buck. */
@StarlarkBuiltin(
    name = "struct",
    doc =
        "A generic object with fields."
            + "<p>Structs fields cannot be reassigned once the struct is created. Two structs are "
            + "equal if they have the same fields and if corresponding field values are equal.")
public final class StructImpl extends Structure implements Info, HasBinary {

  // For a n-element info, the table contains n key strings, sorted,
  // followed by the n corresponding legal Starlark values.
  final Object[] table;

  // A format string with one %s placeholder for the missing field name.
  // If null, uses the default format specified by the provider.
  // TODO(adonovan): make the provider determine the error message
  // (but: this has implications for struct+struct, the equivalence
  // relation, and other observable behaviors).
  @Nullable private final String unknownFieldError;
  private final Provider provider;
  private final Location location;

  private StructImpl(
      Provider provider,
      Object[] table,
      @Nullable Location loc,
      @Nullable String unknownFieldError) {
    this.provider = provider;
    this.location = loc != null ? loc : Location.BUILTIN;
    this.table = table;
    this.unknownFieldError = unknownFieldError;
  }

  // Converts a map to a table of sorted keys followed by corresponding values.
  private static Object[] toTable(Map<String, Object> values) {
    int n = values.size();
    Object[] table = new Object[n + n];
    int i = 0;
    for (Map.Entry<String, Object> e : values.entrySet()) {
      table[i] = e.getKey();
      table[n + i] = Starlark.checkValid(e.getValue());
      i++;
    }
    // Sort keys, permuting values in parallel.
    if (n > 1) {
      sortPairs(table, 0, n - 1);
    }
    return table;
  }

  /**
   * Constructs a StarlarkInfo from an array of alternating key/value pairs as provided by
   * Starlark.fastcall. Checks that each key is provided at most once, and is defined by the
   * optional schema, which must be sorted. This optimized zero-allocation function exists solely
   * for the StarlarkProvider constructor.
   */
  static StructImpl createFromNamedArgs(
      Provider provider, Object[] table, @Nullable ImmutableList<String> schema, Location loc)
      throws EvalException {
    // Permute fastcall form (k, v, ..., k, v) into table form (k, k, ..., v, v).
    permute(table);

    int n = table.length >> 1; // number of K/V pairs

    // Sort keys, permuting values in parallel.
    if (n > 1) {
      sortPairs(table, 0, n - 1);
    }

    // Check for duplicate keys, which are now adjacent.
    for (int i = 0; i < n - 1; i++) {
      if (table[i].equals(table[i + 1])) {
        throw Starlark.errorf(
            "got multiple values for parameter %s in call to instantiate provider %s",
            table[i], provider.getPrintableName());
      }
    }

    // Check that schema is a superset of the table's keys.
    if (schema != null) {
      List<String> unexpected = unexpectedKeys(schema, table, n);
      if (unexpected != null) {
        throw Starlark.errorf(
            "unexpected keyword%s %s in call to instantiate provider %s",
            unexpected.size() > 1 ? "s" : "",
            Joiner.on(", ").join(unexpected),
            provider.getPrintableName());
      }
    }

    return new StructImpl(provider, table, loc, /*unknownFieldError=*/ null);
  }

  // Permutes array elements from alternating keys/values form,
  // (as used by fastcall's named array) into keys-then-corresponding-values form,
  // as used by StarlarkInfo.table.
  // The permutation preserves the key/value association but not the order of keys.
  static void permute(Object[] named) {
    int n = named.length >> 1; // number of K/V pairs

    // Thanks to Murali Ganapathy for the algorithm.
    // See https://play.golang.org/p/QOKnrj_bIwk.
    //
    // i and j are the indices bracketing successive pairs of cells,
    // working from the outside to the middle.
    //
    //   i                  j
    //   [KV]KVKVKVKVKVKV[KV]
    //     i              j
    //   KK[KV]KVKVKVKV[KV]VV
    //       i          j
    //   KKKK[KV]KVKV[KV]VVVV
    //   etc...
    for (int i = 0; i < n - 1; i += 2) {
      int j = named.length - i;
      // rotate two pairs [KV]...[kv] -> [Kk]...[vV]
      Object tmp = named[i + 1];
      named[i + 1] = named[j - 2];
      named[j - 2] = named[j - 1];
      named[j - 1] = tmp;
    }
    // reverse lower half containing keys: [KkvV] -> [kKvV]
    for (int i = 0; i < n >> 1; i++) {
      Object tmp = named[n - 1 - i];
      named[n - 1 - i] = named[i];
      named[i] = tmp;
    }
  }

  // Sorts non-empty slice a[lo:hi] (inclusive) in place.
  // Elements a[n:2n) are permuted the same way as a[0:n),
  // where n = a.length / 2. The lower half must be strings.
  // Precondition: 0 <= lo <= hi < n.
  static void sortPairs(Object[] a, int lo, int hi) {
    String pivot = (String) a[lo + (hi - lo) / 2];

    int i = lo;
    int j = hi;
    while (i <= j) {
      while (((String) a[i]).compareTo(pivot) < 0) {
        i++;
      }
      while (((String) a[j]).compareTo(pivot) > 0) {
        j--;
      }
      if (i <= j) {
        int n = a.length >> 1;
        swap(a, i, j);
        swap(a, i + n, j + n);
        i++;
        j--;
      }
    }
    if (lo < j) {
      sortPairs(a, lo, j);
    }
    if (i < hi) {
      sortPairs(a, i, hi);
    }
  }

  private static void swap(Object[] a, int i, int j) {
    Object tmp = a[i];
    a[i] = a[j];
    a[j] = tmp;
  }

  // Returns the list of keys in table[0:n) not defined by the schema,
  // or null on success.
  // Allocates no memory on success.
  // Both table[0:n) and schema are sorted lists of strings.
  @Nullable
  private static List<String> unexpectedKeys(ImmutableList<String> schema, Object[] table, int n) {
    int si = 0;
    List<String> unexpected = null;
    table:
    for (int ti = 0; ti < n; ti++) {
      String t = (String) table[ti];
      while (si < schema.size()) {
        String s = schema.get(si++);
        int cmp = s.compareTo(t);
        if (cmp == 0) {
          // table key matches schema
          continue table;
        } else if (cmp > 0) {
          // table contains unexpected key
          if (unexpected == null) {
            unexpected = new ArrayList<>();
          }
          unexpected.add(t);
        }
        // else skip over schema key not provided by table
      }
      if (unexpected == null) {
        unexpected = new ArrayList<>();
      }
      unexpected.add(t);
    }
    return unexpected;
  }

  @Override
  public ImmutableCollection<String> getFieldNames() {
    // TODO(adonovan): opt: can we avoid allocating three objects?
    @SuppressWarnings("unchecked")
    List<String> keys = (List<String>) (List<?>) Arrays.asList(table).subList(0, table.length / 2);
    return ImmutableList.copyOf(keys);
  }

  /**
   * Returns the custom (i.e. per-instance, as opposed to per-provider-type) error message string
   * format used by this provider instance, or null if not set.
   */
  @Nullable
  protected String getErrorMessageFormatForUnknownField() {
    return unknownFieldError != null
        ? unknownFieldError
        : getProvider().getErrorMessageFormatForUnknownField();
  }

  @Override
  public boolean isImmutable() {
    // If the provider is not yet exported, the hash code of the object is subject to change.
    // TODO(adonovan): implement isHashable?
    if (!getProvider().isExported()) {
      return false;
    }
    // TODO(bazel-team): If we export at the end of a full module's evaluation, instead of at the
    // end of every top-level statement, then we can assume that exported implies frozen, and just
    // return true here without a traversal.
    for (int i = table.length / 2; i < table.length; i++) {
      if (!Starlark.isImmutable(table[i])) {
        return false;
      }
    }
    return true;
  }

  @Override
  public Object getField(String name) {
    int n = table.length / 2;

    // Try linear search first
    if (n < 100) {
      // Most identifiers in Starlark are interned, so try identity search first
      for (int i = 0; i != n; ++i) {
        if (table[i] == name) {
          return table[n + i];
        }
      }

      // If identity search misses, maybe try linear equality search

      if (n < 20) {
        for (int i = 0; i != n; ++i) {
          if (name.equals(table[i])) {
            return table[n + i];
          }
        }
        // field not found, no reason to fall back to binary search
        return null;
      }
    }

    int i = Arrays.binarySearch(table, 0, n, name);
    if (i < 0) {
      return null;
    }
    return table[n + i];
  }

  /**
   * Creates a schemaless provider instance with the given provider type and field values.
   *
   * <p>{@code loc} is the creation location for this instance. Built-in provider instances may use
   * {@link Location#BUILTIN}, which is the default if null.
   */
  public static StructImpl create(
      Provider provider, Map<String, Object> values, @Nullable Location loc) {
    return new StructImpl(provider, toTable(values), loc, /*unknownFieldError=*/ null);
  }

  /**
   * Creates a schemaless provider instance with the given provider type, field values, and
   * unknown-field error message.
   *
   * <p>This is used to create structs for special purposes, such as {@code ctx.attr} and the {@code
   * native} module. The creation location will be {@link Location#BUILTIN}.
   *
   * <p>{@code unknownFieldError} is a string format, as for {@link
   * Provider#getErrorMessageFormatForUnknownField}.
   *
   * @deprecated Do not use this method. Instead, create a new subclass of {@link NativeProvider}
   *     with the desired error message format, and create a corresponding {@link NativeInfo}
   *     subclass.
   */
  // TODO(bazel-team): Make the special structs that need a custom error message use a different
  // provider (subclassing NativeProvider) and a different StructImpl implementation. Then remove
  // this functionality, thereby saving a string pointer field for the majority of providers that
  // don't need it.
  @Deprecated
  public static StructImpl createWithCustomMessage(
      Provider provider, Map<String, Object> values, String unknownFieldError) {
    Preconditions.checkNotNull(unknownFieldError);
    return new StructImpl(provider, toTable(values), Location.BUILTIN, unknownFieldError);
  }

  @Override
  public StructImpl binaryOp(TokenKind op, Object that, boolean thisLeft) throws EvalException {
    if (op == TokenKind.PLUS && that instanceof StructImpl) {
      return thisLeft
          ? plus(this, (StructImpl) that) //
          : plus((StructImpl) that, this);
    }
    return null;
  }

  private static StructImpl plus(StructImpl x, StructImpl y) throws EvalException {
    Provider xprov = x.getProvider();
    Provider yprov = y.getProvider();
    if (!xprov.equals(yprov)) {
      throw Starlark.errorf(
          "Cannot use '+' operator on instances of different providers (%s and %s)",
          xprov.getPrintableName(), yprov.getPrintableName());
    }

    // ztable = merge(x.table, y.table)
    int xsize = x.table.length / 2;
    int ysize = y.table.length / 2;
    int zsize = xsize + ysize;
    Object[] ztable = new Object[zsize + zsize];
    int xi = 0;
    int yi = 0;
    int zi = 0;
    while (xi < xsize && yi < ysize) {
      String xk = (String) x.table[xi];
      String yk = (String) y.table[yi];
      int cmp = xk.compareTo(yk);
      if (cmp < 0) {
        ztable[zi] = xk;
        ztable[zi + zsize] = x.table[xi + xsize];
        xi++;
      } else if (cmp > 0) {
        ztable[zi] = yk;
        ztable[zi + zsize] = y.table[yi + ysize];
        yi++;
      } else {
        throw Starlark.errorf("cannot add struct instances with common field '%s'", xk);
      }
      zi++;
    }
    while (xi < xsize) {
      ztable[zi] = x.table[xi];
      ztable[zi + zsize] = x.table[xi + xsize];
      xi++;
      zi++;
    }
    while (yi < ysize) {
      ztable[zi] = y.table[yi];
      ztable[zi + zsize] = y.table[yi + ysize];
      yi++;
      zi++;
    }

    return new StructImpl(xprov, ztable, Location.BUILTIN, x.unknownFieldError);
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<instance of provider ");
    printer.append(getProvider().getPrintableName());
    printer.append(">");
  }

  @Override
  public Provider getProvider() {
    return provider;
  }

  @Override
  public Location getCreationLoc() {
    return location;
  }

  /**
   * Returns the result of {@link #getField(String)}, cast as the given type, throwing {@link
   * EvalException} if the cast fails.
   */
  public final <T> T getValue(String key, Class<T> type) throws EvalException {
    Object obj = getField(key);
    if (obj == null) {
      return null;
    }
    try {
      return type.cast(obj);
    } catch (
        @SuppressWarnings("UnusedException")
        ClassCastException unused) {
      throw Starlark.errorf(
          "for %s field, got %s, want %s", key, Starlark.type(obj), Starlark.classType(type));
    }
  }

  @Override
  public String getErrorMessageForUnknownField(String name) {
    String suffix =
        "Available attributes: "
            + Joiner.on(", ").join(Ordering.natural().sortedCopy(getFieldNames()));
    return String.format(getErrorMessageFormatForUnknownField(), name) + "\n" + suffix;
  }

  @Override
  public boolean equals(Object otherObject) {
    if (!(otherObject instanceof StructImpl)) {
      return false;
    }
    StructImpl other = (StructImpl) otherObject;
    if (this == other) {
      return true;
    }
    if (!this.getProvider().equals(other.getProvider())) {
      return false;
    }
    // Compare objects' fields and their values
    if (!this.getFieldNames().equals(other.getFieldNames())) {
      return false;
    }
    for (String field : getFieldNames()) {
      if (!Objects.equal(this.getValueOrNull(field), other.getValueOrNull(field))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    List<String> fields = new ArrayList<>(getFieldNames());
    Collections.sort(fields);
    List<Object> objectsToHash = new ArrayList<>();
    objectsToHash.add(getProvider());
    for (String field : fields) {
      objectsToHash.add(field);
      objectsToHash.add(getValueOrNull(field));
    }
    return Objects.hashCode(objectsToHash.toArray());
  }

  private Object getValueOrNull(String name) {
    return getField(name);
  }

  /** Serialize struct to JSON. */
  @StarlarkMethod(
      name = "to_json",
      doc =
          "Creates a JSON string from the struct parameter. This method only works if all "
              + "struct elements (recursively) are strings, ints, booleans, other structs, a "
              + "list of these types or a dictionary with string keys and values of these types. "
              + "Quotes and new lines in strings are escaped. "
              + "Examples:<br><pre class=language-python>"
              + "struct(key=123).to_json()\n# {\"key\":123}\n\n"
              + "struct(key=True).to_json()\n# {\"key\":true}\n\n"
              + "struct(key=[1, 2, 3]).to_json()\n# {\"key\":[1,2,3]}\n\n"
              + "struct(key='text').to_json()\n# {\"key\":\"text\"}\n\n"
              + "struct(key=struct(inner_key='text')).to_json()\n"
              + "# {\"key\":{\"inner_key\":\"text\"}}\n\n"
              + "struct(key=[struct(inner_key=1), struct(inner_key=2)]).to_json()\n"
              + "# {\"key\":[{\"inner_key\":1},{\"inner_key\":2}]}\n\n"
              + "struct(key=struct(inner_key=struct(inner_inner_key='text'))).to_json()\n"
              + "# {\"key\":{\"inner_key\":{\"inner_inner_key\":\"text\"}}}\n</pre>")
  public String toJson() throws EvalException {
    StringBuilder sb = new StringBuilder();
    StructToJson.printJson(this, sb, "struct field", null);
    return sb.toString();
  }

  @Override
  public String toString() {
    return Starlark.repr(this);
  }
}
