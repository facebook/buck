/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.rules.Label;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Options for including and excluding rules by label.
 * <p>
 * If we were starting from scratch, we'd just have a single command line option: --labels !foo bar
 * <p>
 * However, we have to support the historical dual-option form: --exclude foo --include bar
 * <p>
 * Order is significant as the first matching include/exclude rule wins.  To support the legacy
 * --include and --exclude options and yet still respect order, we use a special option handler
 * {@link com.facebook.buck.cli.TestLabelOptions.LabelsOptionHandler} which keeps track of the
 * argument position of each label-rule in the respective maps populated by those options.
 */
class TestLabelOptions {

  public static final String LABEL_SEPERATOR = "+";

  @Option(
      name = "--exclude",
      usage = "Labels to ignore when running tests, --exclude L1 L2 ... LN.",
      handler = LabelsOptionHandler.class)
  private Map<Integer, LabelSelector> excludedLabelSets;

  @Option(
      name = "--labels",
      aliases = {"--include"},
      usage =
          "Labels to include (or exclude, when prefixed with '!') when running test rules.  " +
          "The first matching statement is used to decide whether to " +
          "include or exclude a test rule.",
      handler = LabelsOptionHandler.class)
  private Map<Integer, LabelSelector> includedLabelSets;

  private Supplier<ImmutableList<LabelSelector>> supplier =
      Suppliers.memoize(new Supplier<ImmutableList<LabelSelector>>() {
        @Override
        public ImmutableList<LabelSelector> get() {
          TreeMap<Integer, LabelSelector> all = Maps.newTreeMap();
          all.putAll(includedLabelSets);

          // Invert the sense of anything given to --exclude.
          // This means we could --exclude !includeMe  ...lolololol
          for (Integer ordinal : excludedLabelSets.keySet()) {
            LabelSelector original = excludedLabelSets.get(ordinal);
            all.put(ordinal, original.invert());
          }

          return ImmutableList.copyOf(all.values());
        }
      });


  public boolean isMatchedByLabelOptions(BuckConfig buckConfig, Set<Label> rawLabels) {
    ImmutableList<LabelSelector> labelSelectors = supplier.get();
    for (LabelSelector labelSelector : labelSelectors) {
      if (labelSelector.matches(rawLabels)) {
        return labelSelector.isInclusive();
      }
    }

    List<String> defaultRawExcludedLabelSelectors =
        buckConfig.getDefaultRawExcludedLabelSelectors();
    for (String raw : defaultRawExcludedLabelSelectors) {
      LabelSelector labelSelector = LabelSelector.fromString(raw).invert();
      if (labelSelector.matches(rawLabels)) {
        return labelSelector.isInclusive();
      }
    }

    boolean defaultResult = true;
    for (LabelSelector labelSelector : labelSelectors) {
      if (labelSelector.isInclusive()) {
        defaultResult = false;
      }
    }

    return defaultResult;
  }

  public static class LabelsOptionHandler extends OptionHandler<Map<Integer, LabelSelector>> {

    /**
     * Shared across all instances of this handler, to keep track of the order of label rules given
     * on the command line.
     */
    private static final AtomicInteger ordinal = new AtomicInteger();

    private final Map<Integer, LabelSelector> labels = Maps.newHashMap();

    public LabelsOptionHandler(
        CmdLineParser parser,
        OptionDef option,
        Setter<Map<Integer, LabelSelector>> setter) throws CmdLineException {
      super(parser, option, setter);
      setter.addValue(labels);
    }

    @Override
    public int parseArguments(Parameters parameters) throws CmdLineException {
      int index;
      for (index = 0; index < parameters.size(); index++) {
        String parameter = parameters.getParameter(index);
        if (parameter.charAt(0) == '-') {
          break;
        }
        labels.put(ordinal.getAndIncrement(), LabelSelector.fromString(parameter));
      }
      return index;
    }

    @Override
    public String getDefaultMetaVariable() {
      return "LIST<LABELS>";
    }
  }
}
