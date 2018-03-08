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

package com.facebook.buck.util.i18n;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.util.Locale;
import java.util.function.Function;
import javax.annotation.concurrent.ThreadSafe;

/** Thread-safe and i18n-safe wrapper for {@link NumberFormat} (which is not thread-safe). */
@ThreadSafe
public class NumberFormatter {
  // Instead of a ThreadLocal<NumberFormat> (which leaks memory),
  // we'll use a LoadingCache of <thread+locale: numberformat> pairs
  // which evicts itself as necessary.
  private final LoadingCache<NumberFormatterCacheKey, NumberFormat> numberFormatCache;

  /**
   * Given a function which creates {@link NumberFormat} objects for a {@link Locale}, returns a
   * wrapper providing thread-safe access to the formatter for that locale.
   */
  public NumberFormatter(Function<Locale, NumberFormat> numberFormatCreator) {
    numberFormatCache =
        CacheBuilder.newBuilder()
            .maximumSize(1000)
            .build(
                new CacheLoader<NumberFormatterCacheKey, NumberFormat>() {
                  @Override
                  public NumberFormat load(NumberFormatterCacheKey key) {
                    return numberFormatCreator.apply(key.getLocale());
                  }
                });
  }

  private NumberFormat getFormatter(Locale locale) {
    return numberFormatCache.getUnchecked(
        NumberFormatterCacheKey.of(Thread.currentThread().getId(), locale));
  }

  /** @see NumberFormat#format(double) */
  public String format(Locale locale, double number) {
    return getFormatter(locale).format(number);
  }

  /** @see NumberFormat#format(double, StringBuffer, FieldPosition) */
  public StringBuffer format(
      Locale locale, double number, StringBuffer toAppendTo, FieldPosition pos) {
    return getFormatter(locale).format(number, toAppendTo, pos);
  }

  /** @see NumberFormat#format(long) */
  public String format(Locale locale, long number) {
    return getFormatter(locale).format(number);
  }

  /** @see NumberFormat#format(Object) */
  public String format(Locale locale, Object object) {
    return getFormatter(locale).format(object);
  }

  /** @see NumberFormat#format(Object, StringBuffer, FieldPosition) */
  public StringBuffer format(
      Locale locale, Object object, StringBuffer toAppendTo, FieldPosition pos) {
    return getFormatter(locale).format(object, toAppendTo, pos);
  }

  /** @see NumberFormat#parse(String) */
  public Number parse(Locale locale, String source) throws ParseException {
    return getFormatter(locale).parse(source);
  }

  /** @see NumberFormat#parse(String, ParsePosition) */
  public Number parse(Locale locale, String source, ParsePosition pos) {
    return getFormatter(locale).parse(source, pos);
  }

  /** @see NumberFormat#parseObject(String, ParsePosition) */
  public Object parseObject(Locale locale, String source, ParsePosition pos) {
    return getFormatter(locale).parseObject(source, pos);
  }
}
