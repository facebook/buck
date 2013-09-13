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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
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
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.io.InputSupplier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    this(HashCode.fromBytes(hashStringToBytes(hashString)));
  }

  private static byte[] hashStringToBytes(String hashString) {
    byte[] bytes = new byte[hashString.length() / 2];
    for (int i = 0; i < hashString.length(); i += 2) {
      char highOrderBits = hashString.charAt(i);
      char lowOrderBits = hashString.charAt(i + 1);
      bytes[i / 2] = (byte)
          ((Byte.parseByte(String.valueOf(highOrderBits), 16) << 4) +
          Byte.parseByte(String.valueOf(lowOrderBits), 16));
    }
    return bytes;
  }

  public HashCode getHashCode() {
    return hashCode;
  }

  /** @return the {@link #toString} of the hash code that underlies this RuleKey. */
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
    RuleKey that = (RuleKey)obj;
    return Objects.equal(this.getHashCode(), that.getHashCode());
  }

  @Override
  public int hashCode() {
    return this.getHashCode().hashCode();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for a {@link RuleKey} that is a function of all of a {@link BuildRule}'s input
   * arguments.
   */
  public static Builder builder(BuildRule rule) throws IOException {
    return builder(rule, /* includeDeps */ true);
  }

  /**
   * Builder for a {@link RuleKey} that is a function of all of a {@link BuildRule}'s input
   * arguments <em>except</em> for its <code>deps</doe>.
   */
  public static Builder builderWithoutDeps(BuildRule rule) throws IOException {
    return builder(rule, /* includeDeps */ false);
  }

  private static Builder builder(BuildRule rule, boolean includeDeps) throws IOException {
    Builder builder = new Builder()
        .set("name", rule.getFullyQualifiedName())

        // Keyed as "buck.type" rather than "type" in case a build rule has its own "type" argument.
        .set("buck.type", rule.getType().getName());

    if (includeDeps) {
      builder.setKey("deps");
      // Note that getDeps() returns an ImmutableSortedSet, so the order will be stable.
      for (BuildRule buildRule : rule.getDeps()) {
        builder.setVal(buildRule.getRuleKey());
      }
    }
    builder.separate();

    return builder;
  }

  public static class Builder {
    private static final String BUCK_VERSION_UID_KEY = "buck.version_uid";

    @VisibleForTesting
    static final String buckVersionUID = System.getProperty(BUCK_VERSION_UID_KEY, "N/A");

    @VisibleForTesting
    static final byte SEPARATOR = '\0';

    private static final Logger logger = Logger.getLogger(Builder.class.getName());

    private final Hasher hasher;
    private boolean idempotent;
    @Nullable private List<String> logElms;

    private Builder() {
      hasher = Hashing.sha1().newHasher();
      idempotent = true;
      if (logger.isLoggable(Level.INFO)) {
        logElms = Lists.newArrayList();
      }
      setBuckVersionUID();
    }

    private Builder(String header) {
      this();
      setHeader(header);
    }

    private Builder feed(byte[] bytes) {
      hasher.putBytes(bytes);
      return this;
    }

    private Builder separate() {
      hasher.putByte(SEPARATOR);
      return this;
    }

    private void setBuckVersionUID() {
      if (logElms != null) {
        logElms.add(String.format("buckVersionUID(%s):", buckVersionUID));
      }
      feed(buckVersionUID.getBytes()).separate();
    }

    private void setHeader(String header) {
      if (logElms != null) {
        logElms.add(String.format("header(%s):", header));
      }
      feed(header.getBytes()).separate();
    }

    private Builder setKey(String sectionLabel) {
      if (logElms != null) {
        logElms.add(String.format(":key(%s):", sectionLabel));
      }
      return separate().feed(sectionLabel.getBytes()).separate();
    }

    private Builder setVal(@Nullable File file) throws IOException {
      if (file != null) {
        // Compute a separate SHA-1 for the file contents and feed that into messageDigest rather
        // than the file contents, in order to avoid the overhead of escaping SEPARATOR in the file
        // content.
        InputSupplier<? extends InputStream> inputSupplier = Files.newInputStreamSupplier(file);
        HashCode fileSha1 = ByteStreams.hash(inputSupplier, Hashing.sha1());

        if (logElms != null) {
          logElms.add(String.format("file(path=\"%s\", sha1=%s):", file.getPath(),
              fileSha1.toString()));
        }
        feed(fileSha1.asBytes());
      }
      return separate();
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

    public Builder set(String key, @Nullable File val) throws IOException {
      return setKey(key).setVal(val);
    }

    public Builder set(String key, @Nullable Path path) throws IOException {
      return set(key, path == null ? null : path.toFile());
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

    public Builder set(String key, @Nullable BuildRule val) throws IOException {
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

    public Builder setInputs(String key, @Nullable Iterable<InputRule> val) throws IOException {
      setKey(key);
      if (val != null) {
        for (InputRule inputRule : val) {
          setVal(inputRule.getRuleKey());
        }
      }
      return separate();
    }

    public Builder setSourcePaths(String key, @Nullable ImmutableSortedSet<SourcePath> val) {
      setKey(key);
      if (val != null) {
        for (SourcePath path : val) {
          setVal(path.asReference());
        }
      }
      return separate();
    }

    public Builder set(String key, @Nullable ImmutableSortedSet<? extends BuildRule> val)
        throws IOException {
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

    public RuleKey build() {
      RuleKey ruleKey = idempotent ? new RuleKey(hasher.hash()) : new RuleKey((HashCode)null);
      if (logElms != null) {
        logger.info(String.format("RuleKey %s=%s", ruleKey, Joiner.on("").join(logElms)));
      }
      return ruleKey;
    }
  }
}
