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

package com.facebook.buck.util.concurrent;

import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class LinkedBlockingStackTest {

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void worksAsAStack() throws InterruptedException {
    LinkedBlockingStack<Integer> stack = new LinkedBlockingStack<>();

    assertThat(stack.add(4), is(true));
    assertThat(stack.add(1), is(true));
    assertThat(stack.offer(6), is(true));
    assertThat(stack.offer(1, 1, TimeUnit.HOURS), is(true));
    stack.put(7);
    stack.put(8);

    assertThat(stack.remove(), is(8));
    assertThat(stack.poll(), is(7));
    assertThat(stack.take(), is(1));
    assertThat(stack.take(), is(6));

    stack.put(3);
    assertThat(stack.remove(), is(3));
    assertThat(stack.poll(1, TimeUnit.HOURS), is(1));
    assertThat(stack.take(), is(4));

    assertThat(stack.poll(), is(nullValue()));
  }

  @Test
  public void elementThrowsOnEmptyStack() {
    LinkedBlockingStack<Integer> stack = new LinkedBlockingStack<>();

    exception.expect(NoSuchElementException.class);
    stack.element();
  }

  @Test
  public void removeThrowsOnEmptyStack() {
    LinkedBlockingStack<Integer> stack = new LinkedBlockingStack<>();

    exception.expect(NoSuchElementException.class);
    stack.remove();
  }

  @Test
  public void sizeEmptinessAndCapacity() throws InterruptedException {
    LinkedBlockingStack<Integer> stack = new LinkedBlockingStack<>();

    assertThat(stack.isEmpty(), is(true));
    assertThat(stack.size(), is(0));
    assertThat(stack.remainingCapacity(), is(Integer.MAX_VALUE));
    stack.put(10);
    stack.put(11);
    stack.put(12);
    assertThat(stack.isEmpty(), is(false));
    assertThat(stack.size(), is(3));
    assertThat(stack.remainingCapacity(), is(Integer.MAX_VALUE - 3));
    stack.take();
    stack.take();
    stack.take();
    assertThat(stack.isEmpty(), is(true));
    assertThat(stack.size(), is(0));
    assertThat(stack.remainingCapacity(), is(Integer.MAX_VALUE));
  }

  @Test
  public void addAllIterationAndClear() {
    LinkedBlockingStack<Integer> stack = new LinkedBlockingStack<>();

    stack.addAll(ImmutableList.of(42, 43, 44, 45));
    assertThat(stack, contains(45, 44, 43, 42));
    stack.clear();
    assertThat(stack.isEmpty(), is(true));
  }

  @Test
  public void removeSpecificObject() {
    LinkedBlockingStack<Integer> stack = new LinkedBlockingStack<>();

    stack.addAll(ImmutableList.of(42, 45, 43, 45, 46));
    stack.remove(45);
    assertThat(stack, contains(46, 43, 45, 42));
  }

  @Test
  public void containsAndContainsAll() throws InterruptedException {
    LinkedBlockingStack<Integer> stack = new LinkedBlockingStack<>();

    assertThat(stack.contains(1), is(false));
    assertThat(stack.contains(2), is(false));
    assertThat(stack.containsAll(ImmutableList.of(1, 2)), is(false));
    stack.put(2);
    assertThat(stack.contains(1), is(false));
    assertThat(stack.contains(2), is(true));
    assertThat(stack.containsAll(ImmutableList.of(1, 2)), is(false));
    stack.put(1);
    assertThat(stack.contains(1), is(true));
    assertThat(stack.contains(2), is(true));
    assertThat(stack.containsAll(ImmutableList.of(1, 2)), is(true));
    stack.take();
    assertThat(stack.contains(1), is(false));
    assertThat(stack.contains(2), is(true));
    assertThat(stack.containsAll(ImmutableList.of(1, 2)), is(false));
    stack.take();
    assertThat(stack.contains(1), is(false));
    assertThat(stack.contains(2), is(false));
    assertThat(stack.containsAll(ImmutableList.of(1, 2)), is(false));
  }

  @Test
  public void retainAllAndRemoveAll() {
    LinkedBlockingStack<Integer> stack = new LinkedBlockingStack<>();

    stack.addAll(ImmutableList.of(5, 8, 3, 6, 7, 3, 6, 4));
    stack.removeAll(ImmutableList.of(3, 4, 9));
    assertThat(stack, contains(6, 7, 6, 8, 5));
    stack.retainAll(ImmutableList.of(4, 6, 8));
    assertThat(stack, contains(6, 6, 8));
  }

  @Test
  public void toArray() {
    LinkedBlockingStack<Integer> stack = new LinkedBlockingStack<>();

    stack.addAll(ImmutableList.of(5, 8, 3, 6, 7));
    assertThat(stack.toArray(), is(arrayContaining(7, 6, 3, 8, 5)));
    assertThat(stack.toArray(new Integer[7]), is(arrayContaining(7, 6, 3, 8, 5, null, null)));
  }

  @Test
  public void drainTo() {
    LinkedBlockingStack<Integer> stack = new LinkedBlockingStack<>();
    ArrayList<Integer> list = new ArrayList<>();

    stack.addAll(ImmutableList.of(5, 8, 3, 6, 7));
    stack.drainTo(list);
    assertThat(list, contains(7, 6, 3, 8, 5));
    assertThat(stack.isEmpty(), is(true));

    list.clear();
    stack.addAll(ImmutableList.of(5, 8, 3, 6, 7));
    stack.drainTo(list, 2);
    assertThat(list, contains(7, 6));
    assertThat(stack, contains(3, 8, 5));
  }
}
