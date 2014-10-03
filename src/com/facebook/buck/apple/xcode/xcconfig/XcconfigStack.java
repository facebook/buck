/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode.xcconfig;

// This class depends on javacc-generated source code.
// Please run "ant compile-tests" if you hit undefined symbols in IntelliJ.

import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Representation of the build settings entailed by a stack of .xcconfig files
 *
 * Included files are inlined and inherits are resolved using private intermediate variables.
 */
@NotThreadSafe
public final class XcconfigStack {

  private final ImmutableMultimap<String, PredicatedConfigValue> configuration;

  private XcconfigStack(ImmutableMultimap<String, PredicatedConfigValue> configuration) {
    this.configuration = configuration;
  }

  /**
   * Dump the configuration stack.
   *
   * @return  A list of build setting strings.
   */
  public ImmutableList<String> resolveConfigStack() {
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    for (String key : configuration.keySet()) {
      for (PredicatedConfigValue config : configuration.get(key)) {
        lines.add(config.toString());
      }
    }
    return lines.build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder class for XcconfigStack objects.
   *
   * Layers and settings are taken by increasing order of priority (last one wins).
   * $(inherited) tokens are replaced by (internal, generated, indirect) references to the previous
   * layers.
   */
  public static class Builder {

    private static final String INHERITED = "inherited";

    private ListMultimap<String, PredicatedConfigValue> stack;
    private Map<String, String> keyAliases;
    private List<PredicatedConfigValue> currentLayer;
    private Map<String, String> currentKeyAliases;
    private int currentCounter;
    private String prefix;

    public Builder(String aliasPrefix) {
      stack = ArrayListMultimap.create();
      keyAliases = Maps.newHashMap();
      currentLayer = Lists.newArrayList();
      currentKeyAliases = Maps.newHashMap();
      currentCounter = 0;
      prefix = aliasPrefix;
    }

    public Builder() {
      this("__");
    }

    public void addSettingsFromFile(
        ProjectFilesystem filesystem, ImmutableList<Path> searchPaths, Path xcconfigPath) {
      ImmutableList<PredicatedConfigValue> settings;
      try {
        settings = XcconfigParser.parse(filesystem, xcconfigPath, searchPaths);
      } catch (ParseException e) {
        // combine the error messages, which may contain a hierarchy of file inclusions, into a
        // single human readable message
        String combinedMessage = Joiner.on('\n').join(
            Iterables.transform(Throwables.getCausalChain(e), new Function<Throwable, String>() {
              @Override
              public String apply(Throwable input) {
                return input.getMessage();
              }
            }));
        throw new HumanReadableException(e, combinedMessage);
      }
      for (PredicatedConfigValue setting : settings) {
        addSetting(setting);
      }
    }

    public void addSetting(String keyCond, String value) {
      try {
        addSetting(XcconfigParser.parseSetting(String.format("%s = %s", keyCond, value)));
      } catch (ParseException e) {
        throw new HumanReadableException(e,
            "Failed to parse xcconfig setting:\n" +
                "  key: %s\n" +
                "  val: %s",
            keyCond, value);
      }
    }

    private static void addSettingToLayer(ListMultimap<String, PredicatedConfigValue> layer,
                                          PredicatedConfigValue setting) {
      List<PredicatedConfigValue> existingSettings = layer.get(setting.key);
      int size = existingSettings.size();
      // We can safely drop the last setting when conditions are identical.
      // By induction, the second last one is already different so no need for a loop.
      //
      // However, removing or replacing settings in the middle of the list is unsafe:
      //   X[a=0] = 1
      //   X[b=0] = 2
      //   X[a=0] = 3
      // may (and should) not give the same value as
      //   X[a=0] = 3
      //   X[b=0] = 2
      // in the case [a=0,b=0]
      if (size > 0 && existingSettings.get(size - 1).conditions.equals(setting.conditions)) {
        existingSettings.remove(size - 1);
      }
      existingSettings.add(setting);
    }

    private String getCurrentAlias(String key) {
      String alias = currentKeyAliases.get(key);
      if (alias == null) {
        alias = prefix + currentCounter + "_" + key;
        currentKeyAliases.put(key, alias);
      }
      return alias;
    }

    public void pushLayer() {
      keyAliases.putAll(currentKeyAliases);
      for (PredicatedConfigValue setting : currentLayer) {
        addSettingToLayer(stack, setting);
      }
      currentLayer = new ArrayList<PredicatedConfigValue>();
      currentKeyAliases = Maps.newHashMap();
      currentCounter += 1;
    }

    private static Function<String, Optional<TokenValue>> solveInherits(
        final String key,
        final TokenValue inheritedValue) {
      return new Function<String, Optional<TokenValue>>() {
        @Override
        public Optional<TokenValue> apply(String name) {
          if (name.equals(INHERITED) || name.equals(key)) {
            return Optional.of(inheritedValue);
          }
          return Optional.absent();
        }
      };
    }

    public void addSetting(PredicatedConfigValue setting) {
      String key = setting.key;

      TokenValue inheritedValue;
      String previousAlias = keyAliases.get(key);
      if (previousAlias != null) {
        inheritedValue = TokenValue.interpolation(previousAlias);
      } else {
        inheritedValue = TokenValue.interpolation(INHERITED);
      }

      ImmutableList<TokenValue> valueTokens = TokenValue.interpolateList(
          solveInherits(key, inheritedValue),
          setting.valueTokens);

      // create a new alias if needed, add the private setting
      String alias = getCurrentAlias(key);
      PredicatedConfigValue privateSetting = new PredicatedConfigValue(
          alias,
          setting.conditions,
          valueTokens);
      currentLayer.add(privateSetting);
    }

    public XcconfigStack build() {
      pushLayer();

      ImmutableMultimap.Builder<String, PredicatedConfigValue> builder =
          ImmutableMultimap.builder();
      List<String> keys = new ArrayList<String>(stack.keySet());
      Collections.sort(keys);
      for (String key : keys) {
        builder.putAll(key, stack.get(key));
      }

      ImmutableSortedSet<Condition> empty = ImmutableSortedSet.<Condition>of();
      keys = new ArrayList<String>(keyAliases.keySet());
      Collections.sort(keys);
      for (String aliased : keys) {
        builder.put(aliased, new PredicatedConfigValue(
            aliased,
            empty,
            ImmutableList.<TokenValue>of(TokenValue.interpolation(
                    Preconditions.checkNotNull(keyAliases.get(aliased))))));
      }
      return new XcconfigStack(builder.build());
    }
  }

}
