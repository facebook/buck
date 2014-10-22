/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.FileHashCache;
import com.facebook.buck.util.hash.AppendingHasher;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * RuleKey encapsulates regimented computation of SHA-1 keys that incorporate all BuildRule state
 * relevant to idempotency. The RuleKey.Builder API conceptually implements the construction of an
 * ordered map, and the key/val pairs are digested using an internal serialization that guarantees
 * a 1:1 mapping for each distinct vector of keys
 * &lt;header,k1,...,kn> in RuleKey.builder(header).set(k1, v1) ... .set(kn, vn).build().
 * <p>
 * Note carefully that in order to reliably avoid accidental collisions, each RuleKey schema, as
 * defined by the key vector, must have a distinct header. Otherwise it is possible (if unlikely)
 * for serialized value data to alias serialized key data, with the result being identical RuleKeys
 * for differing input. In practical terms this means that each BuildRule implementer should specify
 * a distinct header, and that for all RuleKeys built with a particular header, the sequence
 * of set() calls should be identical, even if values are missing. The set() methods specifically
 * handle null values to accommodate this regime.
 */
public class RuleKey {

  private final HashCode hashCode;

  private RuleKey(HashCode hashCode) {
    this.hashCode = Preconditions.checkNotNull(hashCode);
  }

  /**
   * @param hashString string that conforms to the contract of the return value of
   *     {@link com.google.common.hash.HashCode#toString()}.
   */
  public RuleKey(String hashString) {
    this(HashCode.fromString(hashString));
  }

  public HashCode getHashCode() {
    return hashCode;
  }

  /** @return the {@code toString()} of the hash code that underlies this RuleKey. */
  @Override
  public String toString() {
    return getHashCode().toString();
  }

  /**
   * Takes a string and uses it to construct a {@link RuleKey}.
   * <p>
   * Is likely particularly useful with {@link Optional#transform(Function)}.
   */
  public static final Function<String, RuleKey> TO_RULE_KEY =
      new Function<String, RuleKey>() {
        @Override
        public RuleKey apply(String hash) {
          return new RuleKey(hash);
        }
  };

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof RuleKey)) {
      return false;
    }
    RuleKey that = (RuleKey) obj;
    return Objects.equal(this.getHashCode(), that.getHashCode());
  }

  @Override
  public int hashCode() {
    return this.getHashCode().hashCode();
  }

  /**
   * Builder for a {@link RuleKey} that is a function of all of a {@link BuildRule}'s inputs.
   */
  public static Builder builder(
      BuildRule rule,
      SourcePathResolver resolver,
      FileHashCache hashCache) {
    ImmutableSortedSet<BuildRule> exportedDeps;
    if (rule instanceof ExportDependencies) {
      exportedDeps = ((ExportDependencies) rule).getExportedDeps();
    } else {
      exportedDeps = ImmutableSortedSet.of();
    }
    return builder(
        rule.getBuildTarget(),
        rule.getType(),
        resolver,
        rule.getDeps(),
        exportedDeps,
        hashCache);
  }

  /**
   * Builder for a {@link RuleKey} that is a function of all of a {@link BuildRule}'s inputs.
   */
  public static Builder builder(
      BuildTarget name,
      BuildRuleType type,
      SourcePathResolver resolver,
      ImmutableSortedSet<BuildRule> deps,
      ImmutableSortedSet<BuildRule> exportedDeps,
      FileHashCache hashCache) {
    return new Builder(resolver, deps, exportedDeps, hashCache)
        .set("name", name.getFullyQualifiedName())
        // Keyed as "buck.type" rather than "type" in case a build rule has its own "type" argument.
        .set("buck.type", type.getName());
  }

  public static class Builder {

    @VisibleForTesting
    static final byte SEPARATOR = '\0';

    private static final Logger logger = Logger.get(Builder.class);

    private final SourcePathResolver resolver;
    private final ImmutableSortedSet<BuildRule> deps;
    private final ImmutableSortedSet<BuildRule> exportedDeps;
    private final Hasher hasher;
    private final FileHashCache hashCache;

    @Nullable private List<String> logElms;

    private Builder(
        SourcePathResolver resolver,
        ImmutableSortedSet<BuildRule> deps,
        ImmutableSortedSet<BuildRule> exportedDeps,
        FileHashCache hashCache) {
      this.resolver = Preconditions.checkNotNull(resolver);
      this.deps = Preconditions.checkNotNull(deps);
      this.exportedDeps = Preconditions.checkNotNull(exportedDeps);
      this.hasher = new AppendingHasher(Hashing.sha1(), /* numHashers */ 2);
      this.hashCache = Preconditions.checkNotNull(hashCache);
      if (logger.isVerboseEnabled()) {
        this.logElms = Lists.newArrayList();
      }
    }

    private Builder feed(byte[] bytes) {
      hasher.putBytes(bytes);
      return this;
    }

    private Builder separate() {
      hasher.putByte(SEPARATOR);
      return this;
    }

    private Builder setKey(String sectionLabel) {
      if (logElms != null) {
        logElms.add(String.format(":key(%s):", sectionLabel));
      }
      return separate().feed(sectionLabel.getBytes()).separate();
    }

    private Builder setVal(@Nullable String s) {
      if (s != null) {
        if (logElms != null) {
          logElms.add(String.format("string(\"%s\"):", s));
        }
        feed(s.getBytes());
      }
      return separate();
    }

    private Builder setVal(boolean b) {
      if (logElms != null) {
        logElms.add(String.format("boolean(\"%s\"):", b ? "true" : "false"));
      }
      return feed((b ? "t" : "f").getBytes()).separate();
    }

    private Builder setVal(long value) {
      if (logElms != null) {
        logElms.add(String.format("long(\"%s\"):", value));
      }
      hasher.putLong(value);
      separate();
      return this;
    }

    private Builder setVal(@Nullable RuleKey ruleKey) {
      if (ruleKey != null) {
        if (logElms != null) {
          logElms.add(String.format("ruleKey(sha1=%s):", ruleKey));
        }
        feed(ruleKey.toString().getBytes());
      }
      return separate();
    }

    public Builder set(String key, @Nullable String val) {
      return setKey(key).setVal(val);
    }

    public Builder set(String key, Optional<String> val) {
      return set(key, val.isPresent() ? val.get() : null);
    }

    public Builder set(String key, boolean val) {
      return setKey(key).setVal(val);
    }

    public Builder set(String key, long val) {
      return setKey(key).setVal(val);
    }

    public Builder set(String key, @Nullable RuleKey val) {
      return setKey(key).setVal(val);
    }

    public Builder set(String key, @Nullable BuildRule val) {
      return setKey(key).setVal(val != null ? val.getRuleKey() : null);
    }

    public Builder set(String key, @Nullable ImmutableList<SourceRoot> val) {
      setKey(key);
      if (val != null) {
        for (SourceRoot root : val) {
          setVal(root.getName());
        }
      }
      return separate();
    }

    public Builder set(String key, @Nullable List<String> val) {
      setKey(key);
      if (val != null) {
        for (String s : val) {
          setVal(s);
        }
      }
      return separate();
    }

    /**
     * @param inputs is an {@link Iterator} rather than an {@link Iterable} because {@link Path}
     *     implements {@link Iterable} and we want to protect against passing a single {@link Path}
     *     instead of multiple {@link Path}s.
     */
    public Builder setInputs(String key, Iterator<Path> inputs) {
      Preconditions.checkNotNull(key);
      Preconditions.checkNotNull(inputs);
      setKey(key);
      while (inputs.hasNext()) {
        Path input = inputs.next();
        setInputVal(input);
      }
      return separate();
    }

    public Builder setInput(String key, @Nullable Path input) {
      if (input != null) {
        setKey(key);
        setInputVal(input);
      }
      return separate();
    }

    private Builder setInputVal(Path input) {
      HashCode sha1 = hashCache.get(input);
      if (sha1 == null) {
        throw new RuntimeException("No SHA for " + input);
      }
      return setVal(sha1.toString());
    }

    private Builder setInputVal(SourcePath path) {
      Optional<BuildRule> buildRule = resolver.getRule(path);
      if (buildRule.isPresent()) {
        return setVal(buildRule.get().getRuleKey());
      } else {
        Optional<Path> relativePath = resolver.getRelativePath(path);
        Preconditions.checkState(relativePath.isPresent());
        return setInputVal(relativePath.get());
      }
    }

    /**
     * Hash the value of the given {@link SourcePath}, which is either the {@link RuleKey} in the
     * case of a {@link BuildTargetSourcePath} or the hash of the contents in the case of a
     * {@link PathSourcePath}.
     */
    public Builder setInput(String key, SourcePath input) {
      return setKey(key).setInputVal(input).separate();
    }

    public Builder setSourcePaths(String key, @Nullable ImmutableSortedSet<SourcePath> val) {
      setKey(key);
      if (val != null) {
        for (SourcePath path : val) {
          setVal(path.toString());
          Optional<BuildRule> buildRule = resolver.getRule(path);
          if (buildRule.isPresent()) {
            setVal(buildRule.get().getRuleKey());
          } else {
            Optional<Path> relativePath = resolver.getRelativePath(path);
            Preconditions.checkState(relativePath.isPresent());
            setVal(relativePath.get().toString());
          }
        }
      }
      return separate();
    }

    public Builder set(String key, @Nullable ImmutableSortedSet<? extends BuildRule> val) {
      setKey(key);
      if (val != null) {
        for (BuildRule buildRule : val) {
          setVal(buildRule.getRuleKey());
        }
      }
      return separate();
    }

    public Builder set(String key, @Nullable ImmutableSet<String> val) {
      setKey(key);
      if (val != null) {
        ImmutableSortedSet<String> sortedValues = ImmutableSortedSet.copyOf(val);
        for (String value : sortedValues) {
          setVal(value);
        }
      }
      return separate();
    }

    @SuppressWarnings("unchecked")
    public Builder setReflectively(String key, @Nullable Object val) {
      if (val == null) {
        // Doesn't matter what we call. Fast path out.
        set(key, (String) null);
        return this;
      }

      // Let it be stated here for the record that double dispatch is an ugly way to handle this. If
      // java did proper message passing, we could avoid this mess. Oh well.

      // Handle simple types first.
      if (val instanceof Boolean) {
        return set(key, (boolean) val);
      } else if (val instanceof BuildRule) {
        return set(key, (BuildRule) val);
      } else if (val instanceof Long) {
        return set(key, (long) val);
      } else if (val instanceof Path) {
        return setInput(key, (Path) val);
      } else if (val instanceof SourcePath) {
        return setInput(key, (SourcePath) val);
      } else if (val instanceof RuleKey) {
        return set(key, (RuleKey) val);
      }

      // Optionals should be handled reflectively.
      if (val instanceof Optional) {
        // It's actually safe to assume that this is a String, since that's the only Optional type
        // accepted on a set method, but this seems a little more flexible.
        Object o = ((Optional<?>) val).orNull();
        return setReflectively(key, o);
      }

      // Collections. The general strategy is to check the first element to determine the method to
      // call. If the collection is empty, default to pretending we're dealing with an empty
      // collection of strings.
      if (val instanceof List) {
        Object determinant = ((List<?>) val).isEmpty() ? null : ((List<?>) val).get(0);

        if (determinant instanceof SourceRoot) {
          return set(key, ImmutableList.copyOf((List<SourceRoot>) val));
        } else if (determinant instanceof String) {
          return set(key, (List<String>) val);
        } else {
          // Coerce the elements of the collection to strings.
          setKey(key);
          for (Object item : (List<?>) val) {
            setVal(item == null ? null : String.valueOf(item));
          }
          return separate();
        }
      } else if (val instanceof Set) {
        Object determinant = ((Set<?>) val).isEmpty() ? null : ((Set<?>) val).iterator().next();

        if (determinant instanceof BuildRule) {
          return set(key, ImmutableSortedSet.copyOf((Set<BuildRule>) val));
        } else if (determinant instanceof SourcePath) {
          return setSourcePaths(key, ImmutableSortedSet.copyOf((Set<SourcePath>) val));
        } else {
          // Once again, coerce to strings.
          setKey(key);
          for (Object item : (Set<?>) val) {
            setVal(item == null ? null : String.valueOf(item));
          }
          return separate();
        }
      }

      // Collection-like.
      if (val instanceof Iterator) {
        // The only iterator type we accept is a Path. Easy.
        return setInputs(key, (Iterator<Path>) val);
      }

      // Fall through to setting values as strings.
      return set(key, String.valueOf(val));
    }

    public static class RuleKeyPair {
      private final RuleKey totalRuleKey;
      private final RuleKey ruleKeyWithoutDeps;

      private RuleKeyPair(RuleKey totalRuleKey, RuleKey ruleKeyWithoutDeps) {
        this.totalRuleKey = Preconditions.checkNotNull(totalRuleKey);
        this.ruleKeyWithoutDeps = Preconditions.checkNotNull(ruleKeyWithoutDeps);
      }

      public RuleKey getTotalRuleKey() {
        return totalRuleKey;
      }

      public RuleKey getRuleKeyWithoutDeps() {
        return ruleKeyWithoutDeps;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }

        if (!(o instanceof RuleKeyPair)) {
          return false;
        }

        RuleKeyPair that = (RuleKeyPair) o;

        if (!ruleKeyWithoutDeps.equals(that.ruleKeyWithoutDeps)) {
          return false;
        }

        if (!totalRuleKey.equals(that.totalRuleKey)) {
          return false;
        }

        return true;
      }

      @Override
      public int hashCode() {
        return Objects.hashCode(totalRuleKey, ruleKeyWithoutDeps);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this.getClass())
            .add("totalRuleKey", totalRuleKey)
            .add("ruleKeyWithoutDeps", ruleKeyWithoutDeps)
            .toString();
      }

    }

    public RuleKeyPair build() {
      RuleKey ruleKeyWithoutDeps = new RuleKey(hasher.hash());

      // Now introduce the deps into the RuleKey.
      setKey("deps");
      // Note that getDeps() returns an ImmutableSortedSet, so the order will be stable.
      for (BuildRule buildRule : deps) {
        setVal(buildRule.getRuleKey());
      }
      separate();

      if (!exportedDeps.isEmpty()) {
        setKey("exported_deps");
        for (BuildRule buildRule : exportedDeps) {
          setVal(buildRule.getRuleKey());
        }
        separate();
      }
      RuleKey totalRuleKey = new RuleKey(hasher.hash());

      if (logElms != null) {
        logger.verbose("RuleKey %s=%s", totalRuleKey, Joiner.on("").join(logElms));
      }

      return new RuleKeyPair(totalRuleKey, ruleKeyWithoutDeps);
    }
  }
}
