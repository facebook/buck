/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.hash.AppendingHasher;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Primitives;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.Stack;

import javax.annotation.Nullable;

public class RuleKeyBuilder {

  @VisibleForTesting
  static final byte SEPARATOR = '\0';

  private static final Logger logger = Logger.get(RuleKeyBuilder.class);

  private final SourcePathResolver resolver;
  private final Hasher hasher;
  private final FileHashCache hashCache;
  private Stack<String> keyStack;

  @Nullable
  private List<String> logElms;

  public RuleKeyBuilder(
      SourcePathResolver resolver,
      FileHashCache hashCache) {
    this.resolver = resolver;
    this.hasher = new AppendingHasher(Hashing.sha1(), /* numHashers */ 2);
    this.hashCache = hashCache;
    this.keyStack = new Stack<>();
    if (logger.isVerboseEnabled()) {
      this.logElms = Lists.newArrayList();
    }
  }

  private RuleKeyBuilder feed(byte[] bytes) {
    while (!keyStack.isEmpty()) {
      String key = keyStack.pop();
      if (logElms != null) {
        logElms.add(String.format("key(%s):", key));
      }
      hasher.putBytes(key.getBytes());
      hasher.putByte(SEPARATOR);
    }

    hasher.putBytes(bytes);
    hasher.putByte(SEPARATOR);
    return this;
  }

  protected RuleKeyBuilder setSourcePath(SourcePath sourcePath) {
    // And now we need to figure out what this thing is.
    Optional<BuildRule> buildRule = resolver.getRule(sourcePath);
    if (buildRule.isPresent()) {
      feed(sourcePath.toString().getBytes());
      return setSingleValue(buildRule.get());
    } else {
      Optional<Path> relativePath = resolver.getRelativePath(sourcePath);
      Preconditions.checkState(relativePath.isPresent());
      Path path = relativePath.get();
      return setSingleValue(path);
    }
  }

  protected RuleKeyBuilder setBuildRule(BuildRule rule) {
    return setSingleValue(rule.getRuleKey());
  }

  /**
   * Implementations can override this to provide context-specific caching.
   *
   * @return the {@link RuleKey} to be used for the given {@code appendable}.
   */
  protected RuleKey getAppendableRuleKey(
      SourcePathResolver resolver,
      FileHashCache hashCache,
      RuleKeyAppendable appendable) {
    RuleKeyBuilder subKeyBuilder = new RuleKeyBuilder(resolver, hashCache);
    appendable.appendToRuleKey(subKeyBuilder);
    return subKeyBuilder.build();
  }

  public RuleKeyBuilder setAppendableRuleKey(String key, RuleKeyAppendable appendable) {
    setReflectively(
        key + ".appendableSubKey",
        getAppendableRuleKey(resolver, hashCache, appendable));
    return this;
  }

  public RuleKeyBuilder setReflectively(String key, @Nullable Object val) {
    if (val instanceof RuleKeyAppendable) {
      setAppendableRuleKey(key, (RuleKeyAppendable) val);
      if (!(val instanceof BuildRule)) {
        return this;
      }

      // Explicitly fall through for BuildRule objects so we include
      // their cache keys (which may include more data than
      // appendToRuleKey() does).
    }

    // Optionals get special handling. Unwrap them if necessary and recurse.
    if (val instanceof Optional) {
      Object o = ((Optional<?>) val).orNull();
      return setReflectively(key, o);
    }

    int oldSize = keyStack.size();
    keyStack.push(key);
    try {
      // Check to see if we're dealing with a collection of some description. Note
      // java.nio.file.Path implements "Iterable", so we don't check for that.
      if (val instanceof Collection) {
        val = ((Collection<?>) val).iterator();
        // Fall through to the Iterator handling
      }

      if (val instanceof Iterable && !(val instanceof Path)) {
        val = ((Iterable<?>) val).iterator();
        // Fall through to the Iterator handling
      }

      if (val instanceof Iterator) {
        Iterator<?> iterator = (Iterator<?>) val;
        while (iterator.hasNext()) {
          setReflectively(key, iterator.next());
        }
        return this;
      }

      if (val instanceof Map) {
        if (!(val instanceof SortedMap | val instanceof ImmutableMap)) {
          logger.info(
              "Adding an unsorted map to the rule key (%s). " +
                  "Expect unstable ordering and caches misses: %s",
              key,
              val);
        }
        feed("{".getBytes());
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) val).entrySet()) {
          setReflectively(key, entry.getKey());
          feed(" -> ".getBytes());
          setReflectively(key, entry.getValue());
        }
        return feed("}".getBytes());
      }

      if (val instanceof Multimap) {
        feed("{".getBytes());
        for (Map.Entry<?, ?> entry : ((Multimap<?, ?>) val).asMap().entrySet()) {
          setReflectively(key, entry.getKey());
          feed(" -> ".getBytes());
          setReflectively(key, entry.getValue());
        }
        return feed("}".getBytes());
      }

      if (val instanceof Supplier) {
        Object newVal = ((Supplier<?>) val).get();
        return setReflectively(key, newVal);
      }

      return setSingleValue(val);
    } finally {
      while (keyStack.size() > oldSize) {
        keyStack.pop();
      }
    }
  }

  // Paths get added as a combination of the file name and file hash. If the path is absolute
  // then we only include the file name (assuming that it represents a tool of some kind
  // that's being used for compilation or some such). This does mean that if a user renames a
  // file without changing the contents, we have a cache miss. We're going to assume that this
  // doesn't happen that often in practice.
  public RuleKeyBuilder setPath(Path path) throws IOException {
    HashCode sha1 = hashCache.get(path);
    if (sha1 == null) {
      throw new RuntimeException("No SHA for " + path);
    }
    if (logElms != null) {
      logElms.add(String.format("path(%s:%s):", path, sha1));
    }
    if (path.isAbsolute()) {
      logger.warn(
          "Attempting to add absolute path to rule key. Only using file name: %s", path);
      feed(path.getFileName().toString().getBytes());
    } else {
      feed(path.toString().getBytes());
    }
    feed(sha1.toString().getBytes());
    return this;
  }

  protected RuleKeyBuilder setSingleValue(@Nullable Object val) {

    if (val == null) { // Null value first
      return feed(new byte[0]);
    } else if (val instanceof Boolean) {           // JRE types
      if (logElms != null) {
        logElms.add(String.format("boolean(\"%s\"):", (boolean) val ? "true" : "false"));
      }
      feed(((boolean) val ? "t" : "f").getBytes());
    } else if (val instanceof Enum) {
      feed(String.valueOf(val).getBytes());
    } else if (val instanceof Number) {
      if (logElms != null) {
        logElms.add(String.format("number(%s):", val));
      }
      Class<?> wrapped = Primitives.wrap(val.getClass());
      if (Double.class.equals(wrapped)) {
        hasher.putDouble(((Double) val).doubleValue());
      } else if (Float.class.equals(wrapped)) {
        hasher.putFloat(((Float) val).floatValue());
      } else if (Integer.class.equals(wrapped)) {
        hasher.putInt(((Integer) val).intValue());
      } else if (Long.class.equals(wrapped)) {
        hasher.putLong(((Long) val).longValue());
      } else if (Short.class.equals(wrapped)) {
        hasher.putShort(((Short) val).shortValue());
      } else {
        throw new RuntimeException(("Unhandled number type: " + val.getClass()));
      }
    } else if (val instanceof Path) {
      try {
        setPath((Path) val);
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    } else if (val instanceof String) {
      if (logElms != null) {
        logElms.add(String.format("string(\"%s\"):", val));
      }
      feed(((String) val).getBytes());
    } else if (val instanceof BuildRule) {
      return setBuildRule((BuildRule) val);
    } else if (val instanceof BuildRuleType) {
      if (logElms != null) {
        logElms.add(String.format("ruleKeyType(%s):", val));
      }
      feed(val.toString().getBytes());
    } else if (val instanceof RuleKey) {
      if (logElms != null) {
        logElms.add(String.format("ruleKey(sha1=%s):", val));
      }
      feed(val.toString().getBytes());
    } else if (val instanceof BuildTarget || val instanceof UnflavoredBuildTarget) {
      if (logElms != null) {
        logElms.add(String.format("target(%s):", val));
      }
      feed(((HasBuildTarget) val).getBuildTarget().getFullyQualifiedName().getBytes());
    } else if (val instanceof SourcePath) {
      return setSourcePath((SourcePath) val);
    } else if (val instanceof SourceRoot) {
      if (logElms != null) {
        logElms.add(String.format("sourceroot(%s):", val));
      }
      feed(((SourceRoot) val).getName().getBytes());
    } else if (val instanceof SourceWithFlags) {
      SourceWithFlags source = (SourceWithFlags) val;
      setSingleValue(source.getSourcePath());
      feed("[".getBytes());
      for (String flag : source.getFlags()) {
        feed(flag.getBytes());
        feed(",".getBytes());
      }
      feed("]".getBytes());
    } else if (val instanceof Sha1HashCode) {
      setSingleValue(((Sha1HashCode) val).getHash());
    } else {
      throw new RuntimeException("Unsupported value type: " + val.getClass());
    }

    return this;
  }

  public RuleKey build() {
    RuleKey ruleKey = new RuleKey(hasher.hash());
    if (logElms != null) {
      logger.verbose("RuleKey %s=%s", ruleKey, Joiner.on("").join(logElms));
    }
    return ruleKey;
  }

}
