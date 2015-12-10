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

package com.facebook.buck.i18n;

import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Thread-safe and i18n-safe wrapper for {@link NumberFormat} (which is not thread-safe).
 */
@ThreadSafe
public class NumberFormatter {
  // Instead of a ThreadLocal<NumberFormat> (which leaks memory),
  // we'll use a BlockingQueue to hold O(num cores) NumberFormats per
  // locale and stash it in a LoadingCache, which can evict itself if needed.
  private final LoadingCache<Locale, BlockingQueue<NumberFormat>> numberFormatCache;

  // try-with-resources wrapper to take an item from a queue and put it back when done.
  @NotThreadSafe
  private static class BlockingQueueTaker<T> implements AutoCloseable {
    private final BlockingQueue<T> blockingQueue;
    private T item;

    public BlockingQueueTaker(BlockingQueue<T> blockingQueue) {
      this.blockingQueue = blockingQueue;
    }

    public T take() {
      try {
        item = blockingQueue.take();
        return item;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }

    @Override
    public void close() {
      if (item != null) {
        try {
          blockingQueue.put(item);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        } finally {
          item = null;
        }
      }
    }
  }

  /**
   * Given a function which creates {@link NumberFormat} objects for a {@link Locale},
   * returns a wrapper providing thread-safe access to the formatter for that locale.
   */
  public NumberFormatter(final Function<Locale, NumberFormat> numberFormatCreator) {
    numberFormatCache = CacheBuilder.newBuilder().build(
        new CacheLoader<Locale, BlockingQueue<NumberFormat>>() {
            @Override
            public BlockingQueue<NumberFormat> load(Locale locale) throws Exception {
              List<NumberFormat> numberFormats = new ArrayList<>();
              // Add one NumberFormat for each thread so we can format a number
              // in parallel on each thread simulataneously without blocking
              // on the queue.
              int capacity = Runtime.getRuntime().availableProcessors();
              for (int i = 0; i < capacity; i++) {
                numberFormats.add(numberFormatCreator.apply(locale));
              }
              // We pass false for fair since we don't expect to block normally.
              return new ArrayBlockingQueue<>(capacity, /* fair */ false, numberFormats);
            }
        });
  }

  private BlockingQueueTaker<NumberFormat> numberFormatTaker(Locale locale) {
    return new BlockingQueueTaker<>(numberFormatCache.getUnchecked(locale));
  }

  /**
   * @see NumberFormat#format(double)
   */
  public String format(Locale locale, double number) {
    try (BlockingQueueTaker<NumberFormat> formatTaker = numberFormatTaker(locale)) {
      return formatTaker.take().format(number);
    }
  }

  /**
   * @see NumberFormat#format(double, StringBuffer, FieldPosition)
   */
  public StringBuffer format(
      Locale locale,
      double number,
      StringBuffer toAppendTo,
      FieldPosition pos) {
    try (BlockingQueueTaker<NumberFormat> formatTaker = numberFormatTaker(locale)) {
      return formatTaker.take().format(number, toAppendTo, pos);
    }
  }

  /**
   * @see NumberFormat#format(long)
   */
  public String format(Locale locale, long number) {
    try (BlockingQueueTaker<NumberFormat> formatTaker = numberFormatTaker(locale)) {
      return formatTaker.take().format(number);
    }
  }

  /**
   * @see NumberFormat#format(Object)
   */
  public String format(Locale locale, Object object) {
    try (BlockingQueueTaker<NumberFormat> formatTaker = numberFormatTaker(locale)) {
      return formatTaker.take().format(object);
    }
  }

  /**
   * @see NumberFormat#format(Object, StringBuffer, FieldPosition)
   */
  public StringBuffer format(
      Locale locale,
      Object object,
      StringBuffer toAppendTo,
      FieldPosition pos) {
    try (BlockingQueueTaker<NumberFormat> formatTaker = numberFormatTaker(locale)) {
      return formatTaker.take().format(object, toAppendTo, pos);
    }
  }

  /**
   * @see NumberFormat#parse(String)
   */
  public Number parse(Locale locale, String source) throws ParseException {
    try (BlockingQueueTaker<NumberFormat> formatTaker = numberFormatTaker(locale)) {
      return formatTaker.take().parse(source);
    }
  }

  /**
   * @see NumberFormat#parse(String, ParsePosition)
   */
  public Number parse(Locale locale, String source, ParsePosition pos) throws ParseException {
    try (BlockingQueueTaker<NumberFormat> formatTaker = numberFormatTaker(locale)) {
      return formatTaker.take().parse(source, pos);
    }
  }

  /**
   * @see NumberFormat#parseObject(String, ParsePosition)
   */
  public Object parseObject(Locale locale, String source, ParsePosition pos) throws ParseException {
    try (BlockingQueueTaker<NumberFormat> formatTaker = numberFormatTaker(locale)) {
      return formatTaker.take().parseObject(source, pos);
    }
  }
}
