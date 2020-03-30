/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.util.concurrent;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * An implementation of {@link BlockingQueue} interface returning elements in LIFO order.
 *
 * @param <E> Type of contained elements.
 */
public class LinkedBlockingStack<E> implements BlockingQueue<E> {

  private final LinkedBlockingDeque<E> delegate;

  public LinkedBlockingStack() {
    delegate = new LinkedBlockingDeque<>();
  }

  @Override
  public boolean add(E element) {
    delegate.addFirst(element);
    return true;
  }

  @Override
  public boolean offer(E element) {
    return delegate.offerFirst(element);
  }

  @Override
  public E remove() {
    return delegate.removeFirst();
  }

  @Override
  public E poll() {
    return delegate.pollFirst();
  }

  @Override
  public E element() {
    return delegate.getFirst();
  }

  @Override
  public E peek() {
    return delegate.peekFirst();
  }

  @Override
  public void put(E element) throws InterruptedException {
    delegate.putFirst(element);
  }

  @Override
  public boolean offer(E element, long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.offerFirst(element, timeout, unit);
  }

  @Override
  public E take() throws InterruptedException {
    return delegate.takeFirst();
  }

  @Override
  public E poll(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.pollFirst(timeout, unit);
  }

  @Override
  public int remainingCapacity() {
    return delegate.remainingCapacity();
  }

  @Override
  public boolean remove(Object object) {
    return delegate.remove(object);
  }

  @Override
  public boolean addAll(Collection<? extends E> collection) {
    boolean modified = false;
    for (E element : collection) {
      if (add(element)) {
        modified = true;
      }
    }
    return modified;
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  @Override
  public boolean retainAll(Collection<?> collection) {
    return delegate.retainAll(collection);
  }

  @Override
  public boolean removeAll(Collection<?> collection) {
    return delegate.removeAll(collection);
  }

  @Override
  public boolean containsAll(Collection<?> collection) {
    return delegate.containsAll(collection);
  }

  @Override
  public int size() {
    return delegate.size();
  }

  @Override
  public boolean isEmpty() {
    return delegate.isEmpty();
  }

  @Override
  public boolean contains(Object object) {
    return delegate.contains(object);
  }

  @Override
  public Iterator<E> iterator() {
    return delegate.iterator();
  }

  @Override
  public Object[] toArray() {
    return delegate.toArray();
  }

  @Override
  public <T> T[] toArray(T[] array) {
    return delegate.toArray(array);
  }

  @Override
  public int drainTo(Collection<? super E> collection) {
    return delegate.drainTo(collection);
  }

  @Override
  public int drainTo(Collection<? super E> collection, int maxElements) {
    return delegate.drainTo(collection, maxElements);
  }
}
