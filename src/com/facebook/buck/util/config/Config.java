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

package com.facebook.buck.util.config;

import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.model.macros.MacroFinder;
import com.facebook.buck.model.macros.MacroReplacer;
import com.facebook.buck.model.macros.StringMacroCombiner;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Structured representation of data read from a stack of {@code .ini} files, where each file can
 * override values defined by the previous ones.
 */
public class Config {
  /** Used in a string representation of a map; separates pairs of value */
  public static final String DEFAULT_PAIR_SEPARATOR = ",";
  /** Used in a string representation of a map; separates keys from values */
  public static final String DEFAULT_KEY_VALUE_SEPARATOR = "=>";

  // rawConfig is the flattened configuration relevant to the current cell
  private final RawConfig rawConfig;

  private final Supplier<Integer> hashCodeSupplier =
      MoreSuppliers.memoize(
          new Supplier<Integer>() {
            @Override
            public Integer get() {
              return Objects.hashCode(rawConfig);
            }
          });

  /** Caches the expanded value lookups. */
  private final Map<Map.Entry<String, String>, Optional<String>> cache = new ConcurrentHashMap<>();

  /** Convenience constructor to create an empty config. */
  public Config() {
    this(RawConfig.of());
  }

  public Config(RawConfig rawConfig) {
    this.rawConfig = rawConfig;
  }

  // Some `.buckconfig`s embed genrule macros which break with recent changes to support the config
  // macro.  So, add special expanders to preserve these until they get fixed.
  private static MacroReplacer<String> getMacroPreserver(final String name) {
    return input -> String.format("$(%s %s)", name, Joiner.on(' ').join(input.getMacroInput()));
  }

  /** @return the input after recursively expanding any config references. */
  private String expand(String input, final Stack<String> expandStack) {
    MacroReplacer<String> macroReplacer =
        inputs -> {
          if (inputs.getMacroInput().size() != 1) {
            throw new HumanReadableException(
                "references must have a single argument of the form `<section>.<field>`,"
                    + " but was '%s'",
                inputs);
          }
          List<String> parts = Splitter.on('.').limit(2).splitToList(inputs.getMacroInput().get(0));
          if (parts.size() != 2) {
            throw new HumanReadableException(
                "references must have the form `<section>.<field>`, but was '%s'", parts);
          }
          return get(parts.get(0), parts.get(1), expandStack).orElse("");
        };
    try {
      return MacroFinder.replace(
          ImmutableMap.of(
              "config", macroReplacer,
              "exe", getMacroPreserver("exe"),
              "location", getMacroPreserver("location")),
          input,
          true,
          new StringMacroCombiner());
    } catch (MacroException e) {
      throw new HumanReadableException(e, e.getMessage());
    }
  }

  private Optional<String> get(String section, String field, Stack<String> expandStack) {

    // First check if we've cached this expansion, and return it if so.
    Map.Entry<String, String> cacheKey = new AbstractMap.SimpleEntry<>(section, field);
    Optional<String> value = cache.get(cacheKey);
    if (value != null) {
      return value;
    }

    // Check if we're caught in a config-reference cyclical dependency and error out if so.
    String expandStackKey = String.format("%s.%s", section, field);
    if (expandStack.search(expandStackKey) != -1) {
      throw new HumanReadableException(
          "cyclical dependency in config references: %s",
          Joiner.on(" -> ").join(FluentIterable.from(expandStack).append(expandStackKey)));
    }

    Optional<String> val = rawConfig.getValue(section, field);
    if (!val.isPresent()) {
      cache.put(cacheKey, Optional.empty());
      return Optional.empty();
    }

    // Add the current section/field pair to the stack before expansion so we can provide a useful
    // error message for dependency cycles.
    expandStack.push(expandStackKey);
    value = Optional.of(expand(val.get(), expandStack));
    expandStack.pop();

    cache.put(cacheKey, value);
    return value;
  }

  public Optional<String> get(String section, String field) {
    return get(section, field, new Stack<String>());
  }

  public ImmutableMap<String, String> get(String sectionName) {
    ImmutableMap.Builder<String, String> expanded = ImmutableMap.builder();
    for (Map.Entry<String, String> ent : this.rawConfig.getSection(sectionName).entrySet()) {
      expanded.put(ent.getKey(), get(sectionName, ent.getKey()).get());
    }
    return expanded.build();
  }

  public ImmutableMap<String, ImmutableMap<String, String>> getSectionToEntries() {
    ImmutableMap.Builder<String, ImmutableMap<String, String>> expanded = ImmutableMap.builder();
    for (String section : this.rawConfig.getValues().keySet()) {
      expanded.put(section, get(section));
    }
    return expanded.build();
  }

  public ImmutableMap<String, ImmutableMap<String, String>> getRawConfigForDistBuild() {
    return getSectionToEntries();
  }

  /**
   * @return An {@link ImmutableList} containing all entries that don't look like comments, or the
   *     empty list if the property is not defined or there are no values.
   */
  public ImmutableList<String> getListWithoutComments(String sectionName, String propertyName) {
    return getOptionalListWithoutComments(sectionName, propertyName).orElse(ImmutableList.of());
  }

  public ImmutableList<String> getListWithoutComments(
      String sectionName, String propertyName, char splitChar) {
    return getOptionalListWithoutComments(sectionName, propertyName, splitChar)
        .orElse(ImmutableList.of());
  }

  /**
   * ini4j leaves things that look like comments in the values of entries in the file. Generally, we
   * don't want to include these in our parameters, so filter them out where necessary. In an INI
   * file, the comment separator is ";", but some parsers (ini4j included) use "#" too. This method
   * handles both cases.
   *
   * @return an {@link ImmutableList} containing all entries that don't look like comments, the
   *     empty list if the property is defined but there are no values, or Optional.empty() if the
   *     property is not defined.
   */
  public Optional<ImmutableList<String>> getOptionalListWithoutComments(
      String sectionName, String propertyName) {
    // Default split character for lists is comma.
    return getOptionalListWithoutComments(sectionName, propertyName, ',');
  }

  public Optional<ImmutableList<String>> getOptionalListWithoutComments(
      String sectionName, String propertyName, char splitChar) {
    Optional<String> rawValue = get(sectionName, propertyName);
    if (!rawValue.isPresent()) {
      return Optional.empty();
    }
    String value = rawValue.get();
    if (value.isEmpty()) {
      return Optional.of(ImmutableList.of());
    }

    // Reject if the first nonspace character is an ini comment char (';' or '#')
    if (Pattern.compile("^\\s*[#;]").matcher(value).find()) {
      return Optional.of(ImmutableList.of());
    }

    return Optional.of(decodeQuotedParts(value, Optional.of(splitChar), sectionName, propertyName));
  }

  public Optional<String> getValue(String sectionName, String propertyName) {
    Optional<String> rawValue = get(sectionName, propertyName);
    if (rawValue.isPresent()) {
      String value = rawValue.get();
      if (value.isEmpty()) {
        return Optional.empty();
      } else {
        return Optional.of(
            decodeQuotedParts(value, Optional.empty(), sectionName, propertyName).get(0));
      }
    } else {
      return rawValue;
    }
  }

  public Optional<Long> getLong(String sectionName, String propertyName) {
    Optional<String> value = getValue(sectionName, propertyName);
    return value.isPresent() ? Optional.of(Long.valueOf(value.get())) : Optional.empty();
  }

  public Optional<Integer> getInteger(String sectionName, String propertyName) {
    Optional<String> value = getValue(sectionName, propertyName);
    return value.isPresent() ? Optional.of(Integer.valueOf(value.get())) : Optional.empty();
  }

  public Optional<Float> getFloat(String sectionName, String propertyName) {
    Optional<String> value = getValue(sectionName, propertyName);
    if (value.isPresent()) {
      try {
        return Optional.of(Float.valueOf(value.get()));
      } catch (NumberFormatException e) {
        throw new HumanReadableException(
            "Malformed value for %s in [%s]: %s; expecting a floating point number.",
            propertyName, sectionName, value.get());
      }
    } else {
      return Optional.empty();
    }
  }

  public boolean getBooleanValue(String sectionName, String propertyName, boolean defaultValue) {
    return getBoolean(sectionName, propertyName).orElse(defaultValue);
  }

  public Optional<Boolean> getBoolean(String sectionName, String propertyName) {
    Optional<String> value = getValue(sectionName, propertyName);
    if (!value.isPresent()) {
      return Optional.empty();
    }

    String answer = value.get();
    switch (answer.toLowerCase()) {
      case "yes":
      case "true":
        return Optionals.ofBoolean(true);

      case "no":
      case "false":
        return Optionals.ofBoolean(false);

      default:
        throw new HumanReadableException(
            "Unknown value for %s in [%s]: %s; should be yes/no true/false!",
            propertyName, sectionName, answer);
    }
  }

  public <T extends Enum<T>> Optional<T> getEnum(String section, String field, Class<T> clazz) {
    Optional<String> value = getValue(section, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Enum.valueOf(clazz, value.get().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException(
          ".buckconfig: %s:%s must be one of %s (case insensitive) (was \"%s\")",
          section, field, Joiner.on(", ").join(clazz.getEnumConstants()), value.get());
    }
  }

  public Optional<URI> getUrl(String section, String field) {
    try {
      // URL has stricter parsing rules than URI, so we want to use that constructor to surface
      // the error message early. Passing around a URL is problematic as it hits DNS from the
      // equals method, which is why the (new URL(...).toURI()) call instead of just URI.create.
      Optional<String> value = getValue(section, field);
      if (!value.isPresent()) {
        return Optional.empty();
      }
      return Optional.of(new URL(value.get()).toURI());
    } catch (URISyntaxException | MalformedURLException e) {
      throw new HumanReadableException(
          e, "Malformed url [%s]%s: %s", section, field, e.getMessage());
    }
  }

  /**
   * Convert a string representation of a map to a binary {@code ImmutableMap<String, String>}
   *
   * @param section Config file section name
   * @param field Config file value name
   * @param pairSeparator String that separates pairs of keys and values
   * @param keyValueSeparator String that separates keys and values
   * @return An {@link ImmutableMap}
   */
  public ImmutableMap<String, String> getMap(
      String section, String field, String pairSeparator, String keyValueSeparator) {
    return getMap(getValue(section, field), pairSeparator, keyValueSeparator);
  }

  /**
   * Convert an {@code Optional<String>} representation of a map to a binary {@code
   * ImmutableMap<String, String>}
   *
   * @param value An {@code Optional<String>}, such as you might get from {@link #getValue(String,
   *     String)}
   * @param pairSeparator String that separates pairs of keys and values
   * @param keyValueSeparator String that separates keys and values
   * @return An {@link ImmutableMap}
   */
  public static ImmutableMap<String, String> getMap(
      Optional<String> value, String pairSeparator, String keyValueSeparator) {
    if (value.isPresent()) {
      return ImmutableMap.copyOf(
          Splitter.on(pairSeparator)
              .omitEmptyStrings()
              .withKeyValueSeparator(Splitter.on(keyValueSeparator).trimResults())
              .split(value.get()));
    } else {
      return ImmutableMap.of();
    }
  }

  /**
   * Convert a string representation of a map to a binary {@code ImmutableMap<String, String>},
   * using default separators
   *
   * @param section Config file section name
   * @param field Config file value name
   * @return An {@link ImmutableMap}
   */
  public ImmutableMap<String, String> getMap(String section, String field) {
    return getMap(section, field, DEFAULT_PAIR_SEPARATOR, DEFAULT_KEY_VALUE_SEPARATOR);
  }

  /**
   * Convert a {@code Optional<String>} representation of a map to a binary {@code
   * ImmutableMap<String, String>}, using default separators
   *
   * @param value An {@code Optional<String>}, such as you might get from {@link #getValue(String,
   *     String)}
   * @return An {@link ImmutableMap}
   */
  public static ImmutableMap<String, String> getMap(Optional<String> value) {
    return getMap(value, DEFAULT_PAIR_SEPARATOR, DEFAULT_KEY_VALUE_SEPARATOR);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    } else if (!(obj instanceof Config)) {
      return false;
    }
    Config that = (Config) obj;
    return Objects.equal(this.rawConfig, that.rawConfig);
  }

  public boolean equalsIgnoring(
      Config other, ImmutableMap<String, ImmutableSet<String>> ignoredFields) {
    if (this == other) {
      return true;
    }
    ImmutableMap<String, ImmutableMap<String, String>> left = this.getSectionToEntries();
    ImmutableMap<String, ImmutableMap<String, String>> right = other.getSectionToEntries();
    Sets.SetView<String> sections = Sets.union(left.keySet(), right.keySet());
    for (String section : sections) {
      ImmutableMap<String, String> leftFields = left.getOrDefault(section, ImmutableMap.of());
      ImmutableMap<String, String> rightFields = right.getOrDefault(section, ImmutableMap.of());
      Sets.SetView<String> fields =
          Sets.difference(
              Sets.union(leftFields.keySet(), rightFields.keySet()),
              Optional.ofNullable(ignoredFields.get(section)).orElse(ImmutableSet.of()));
      for (String field : fields) {
        String leftValue = leftFields.get(field);
        String rightValue = rightFields.get(field);
        if (leftValue == null || rightValue == null || !leftValue.equals(rightValue)) {
          return false;
        }
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    return hashCodeSupplier.get();
  }

  /**
   * Decodes from a string to a list of strings, splitting on separators. The encoded string may
   * contain double quotes. These inhibit the special meaning of characters inside them, except for
   * backslash and double quote. Double quote ends the quoted part, and backslash begins an escape
   * sequence. The following escape sequences are supported:
   *
   * <p>\ backslash " double quote n newline r carriage return t tab x## unicode character with code
   * point ## (in hex) u#### unicode character with code point #### (in hex) U######## unicode
   * character with code point ######## (in hex)
   *
   * <p>Using this decoding, the resulting strings can contain any unicode character, even the ones
   * that normally would have special meaning.
   *
   * <p>When the splitting character is absent, no splitting is performed and a list containing a
   * single string is returned.
   *
   * <p>Unquoted whitespace is trimmed from the front of values.
   *
   * @param input string to decode
   * @param splitChar character to split on (if absent, no splitting performed)
   * @param section section in the configuration file
   * @param field field in the configuration file
   * @return list of decoded parts (single-item list if splitChar is absent)
   */
  private static ImmutableList<String> decodeQuotedParts(
      String input, Optional<Character> splitChar, String section, String field) {
    ImmutableList.Builder<String> listBuilder = ImmutableList.builder();
    StringBuilder stringBuilder = new StringBuilder();
    boolean inQuotes = false;
    int quoteIndex = 0;
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          inQuotes = false;
          continue;
        } else if (c == '\\') {
          ++i;
          if (i >= input.length()) {
            throw new HumanReadableException(
                ".buckconfig: %s:%s: Input ends inside escape sequence: %s",
                section, field, input.substring(i - 1));
          }
          c = input.charAt(i);
          switch (c) {
            case 'n':
              stringBuilder.append('\n');
              continue;
            case 'r':
              stringBuilder.append('\r');
              continue;
            case 't':
              stringBuilder.append('\t');
              continue;
            case 'U':
              int codePoint = hexDecode(input, i + 1, 8, "\\U", section, field);
              stringBuilder.append(Character.toChars(codePoint));
              i += 8;
              continue;
            case 'u':
              stringBuilder.append((char) hexDecode(input, i + 1, 4, "\\u", section, field));
              i += 4;
              continue;
            case 'x':
              stringBuilder.append((char) hexDecode(input, i + 1, 2, "\\x", section, field));
              i += 2;
              continue;
            case '\\':
            case '"':
              // These characters are added literally.
              break;
            default:
              throw new HumanReadableException(
                  ".buckconfig: %s:%s: Invalid escape sequence: %s",
                  section, field, input.substring(i - 1, i + 1));
          }
        }
      } else if (c == '"') {
        quoteIndex = i;
        inQuotes = true;
        continue;
      } else if (splitChar.isPresent() && c == splitChar.get()) {
        listBuilder.add(stringBuilder.toString());
        stringBuilder = new StringBuilder();
        continue;
      } else if (stringBuilder.length() == 0 && c == ' ' || c == '\t') {
        // Skip unquoted whitespace before value.
        continue;
      }
      // default case: add the actual character
      stringBuilder.append(c);
    }

    if (inQuotes) {
      // We reached the end of the input without finding the closing quote.
      // Show a short sample of the quoted part in the error message.
      int lastIndex = quoteIndex + 10;
      if (lastIndex >= input.length()) {
        lastIndex = input.length();
      }
      throw new HumanReadableException(
          ".buckconfig: %s:%s: " + "Input ends inside quoted string: %s...",
          section, field, input.substring(quoteIndex, lastIndex));
    }

    listBuilder.add(stringBuilder.toString());
    return listBuilder.build();
  }

  public Config overrideWith(Config other) {
    RawConfig.Builder builder = RawConfig.builder();
    builder.putAll(this.rawConfig);
    builder.putAll(other.rawConfig);
    return new Config(builder.build());
  }

  /**
   * Decodes hexadecimal digits from a string.
   *
   * @param string the string to decode the digits from
   * @param begin position to start decoding from
   * @param length number of digits to decode
   * @param prefix characters before the hexadecimal digits (e.g. "\\x")
   * @param section section name in configuration file
   * @param field field name in configuration file
   * @return the decoded value.
   */
  private static int hexDecode(
      String string, int begin, int length, String prefix, String section, String field) {
    int result = 0;
    for (int i = begin; i < begin + length; i++) {
      if (i >= string.length()) {
        throw new HumanReadableException(
            ".buckconfig: %s:%s: " + "Input ends inside hexadecimal sequence: %s%s",
            section, field, prefix, string.substring(begin));
      }
      char c = string.charAt(i);
      if (c >= 'a') {
        // 'a' has value 97. Subtract 87, so 'a' becomes 10, 'b' 11, etc.
        c -= 87;
      } else if (c >= 'A') {
        // 'A' has value 65. Subtract 55, so 'A' becomes 10, 'B' 11, etc.
        c -= 55;
      } else if (c >= '0' && c <= '9') {
        // '0' has value 48, so subtract that, making '0' 0, '1' 1, etc.
        c -= 48;
      } else {
        // not a valid hex char; set c to an invalid value to trigger
        // the exception below.
        c = 255;
      }
      if (c > 16) {
        throw new HumanReadableException(
            ".buckconfig: %s:%s: Invalid hexadecimal digit in sequence: %s%s",
            section, field, prefix, string.substring(begin, i + 1));
      }
      result = (result << 4) | c;
    }
    return result;
  }

  public RawConfig getRawConfig() {
    return rawConfig;
  }
}
