package net.starlark.java.eval;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Similar to {@link java.util.LinkedHashMap} but optimized for Starlark uses.
 *
 * <p>Some differences:
 *
 * <ul>
 *   <li>Internals are exposed (e. g. it is possible to query the map with precomputed hash)
 *   <li>There is no rb-tree to stored nodes in bucket (not really needed for small maps)
 *   <li>There's no mod counters
 * </ul>
 */
class DictMap<K, V> {

  private static final Node<?, ?>[] EMPTY_NODES = {};

  // Hashtable, length is zero or power of two
  private Node<K, V>[] table;
  // First and last nodes for deterministic iteration order
  private Node<K, V> first;
  private Node<K, V> last;
  // Size
  private int size;

  /** Empty map. */
  @SuppressWarnings("unchecked")
  DictMap() {
    table = (Node<K, V>[]) EMPTY_NODES;
    size = 0;
  }

  /** Constructor with expected size. */
  @SuppressWarnings("unchecked")
  DictMap(int expectedSize) {
    table =
        expectedSize == 0
            ? (Node<K, V>[]) EMPTY_NODES
            : (Node<K, V>[]) new Node<?, ?>[capacityForSize(expectedSize)];
    size = 0;
  }

  /** Map size. */
  int size() {
    return size;
  }

  /** Size is zero? */
  boolean isEmpty() {
    return size == 0;
  }

  /** First node. */
  @Nullable
  Node<K, V> getFirst() {
    return first;
  }

  /** Remove the first node from the map. */
  Node<K, V> removeFirst() {
    if (first == null) {
      return null;
    }
    Node<K, V> removed = remove(first.key, first.keyHash);
    Preconditions.checkState(removed != null);
    return removed;
  }

  /** Clear the map. */
  @SuppressWarnings("unchecked")
  public void clear() {
    if (size != 0) {
      table = (Node<K, V>[]) EMPTY_NODES;
      size = 0;
      first = last = null;
    }
  }

  /** Check if the map contains the value. */
  boolean containsValue(V value) {
    Node<K, V> node = first;
    while (node != null) {
      if (node.value.equals(value)) {
        return true;
      }
      node = node.next;
    }
    return false;
  }

  /** Hashtable node. */
  static class Node<K, V> {
    // Key
    final K key;
    // Saved hash
    final int keyHash;
    // Current value
    private V value;

    // Down the bucket
    @Nullable private Node<K, V> down;

    // Prev node in linked list
    private Node<K, V> prev;
    // Next node in linked list
    private Node<K, V> next;

    /** Obtain the node value. */
    V getValue() {
      return value;
    }

    /** Next node in the linked list. */
    @Nullable
    Node<K, V> getNext() {
      return next;
    }

    private Node(K key, int keyHash, V value) {
      this.key = key;
      this.keyHash = keyHash;
      this.value = value;
    }

    /** Node key is equal to given key with precomputed hash? */
    private boolean keyEqual(K key, int keyHash) {
      return keyHash == this.keyHash && (key == this.key || key.equals(this.key));
    }
  }

  /** Lookup node. */
  @Nullable
  Node<K, V> getNode(K key, int keyHash) {
    if (isEmpty()) {
      return null;
    }
    Node<K, V> node = table[keyHash & table.length - 1];
    while (node != null) {
      if (node.keyEqual(key, keyHash)) {
        return node;
      }
      node = node.down;
    }
    return null;
  }

  /** Lookup node. */
  @Nullable
  Node<K, V> getNode(K key) {
    if (isEmpty()) {
      return null;
    }
    return getNode(key, DictHash.hash(key));
  }

  /** Lookup by key. */
  @Nullable
  V get(K key) {
    if (isEmpty()) {
      return null;
    }
    int keyHash = DictHash.hash(key);
    Node<K, V> node = getNode(key, keyHash);
    return node != null ? node.value : null;
  }

  /** Check if key exists. */
  boolean containsKey(K key) {
    return getNode(key) != null;
  }

  /** Contains key variant with prehashed key. */
  boolean containsKey(K key, int keyHash) {
    if (isEmpty()) {
      return false;
    }
    return getNode(key, keyHash) != null;
  }

  /** Contains key/value pair. */
  boolean containsEntry(K key, int keyHash, V value) {
    Node<K, V> node = getNode(key, keyHash);
    return node != null && node.value.equals(value);
  }

  /** Compute capacity for given size. */
  @VisibleForTesting
  static int capacityForSize(int size) {
    if (size == 0) {
      return 0;
    } else if (size <= 8) {
      return 16;
    } else {
      // This return 32 for 9: we don't store a lot of dicts in memory,
      // so better overallocate than deal with hash collisions.
      return Integer.highestOneBit(size - 1) << 2;
    }
  }

  /** Expand hashtable to efficiently store given number of entries. */
  @SuppressWarnings("unchecked")
  private void ensureCapacity(int size) {
    int cap = capacityForSize(size);
    if (cap > table.length) {
      Node<K, V>[] table = (Node<K, V>[]) new Node<?, ?>[cap];

      Node<K, V> node = first;
      while (node != null) {
        int bucket = node.keyHash & table.length - 1;
        node.down = table[bucket];
        table[bucket] = node;
        node = node.next;
      }

      this.table = table;
      checkSelf();
    }
  }

  /** Put an entry into the map performing no entry eviction and no resize. */
  void putNoEvictNoResize(K key, int keyHash, V value) {
    Node<K, V> node = new Node<>(key, keyHash, value);
    // Crash here if the map is empty.
    // But if the map has too little capacity,
    // we won't crash, but will create inefficient map.
    // So use this function carefully.
    int bucket = keyHash & table.length - 1;
    node.down = table[bucket];
    table[bucket] = node;

    if (last == null) {
      // The map is empty.
      first = last = node;
    } else {
      last.next = node;
      node.prev = last;
      last = node;
    }

    ++size;
    checkSelf();
  }

  /** Put a key without evicting existing entry with given key. */
  void putNoEvict(K key, int keyHash, V value) {
    ensureCapacity(size + 1);
    putNoEvictNoResize(key, keyHash, value);
  }

  @Nullable
  V putNoResize(K key, int keyHash, V value) {
    Node<K, V> node = getNode(key, keyHash);
    if (node != null) {
      V prev = node.value;
      node.value = value;
      checkSelf();
      return prev;
    }
    putNoEvictNoResize(key, keyHash, value);
    return null;
  }

  @Nullable
  V put(K key, int keyHash, V value) {
    Node<K, V> node = getNode(key, keyHash);
    if (node != null) {
      V prev = node.value;
      node.value = value;
      checkSelf();
      return prev;
    }
    putNoEvict(key, keyHash, value);
    return null;
  }

  @Nullable
  V putNoResize(K key, V value) {
    return putNoResize(key, DictHash.hash(key), value);
  }

  @Nullable
  V put(K key, V value) {
    return put(key, DictHash.hash(key), value);
  }

  /** Update this map with given map. */
  <V2 extends V, K2 extends K> void putAll(DictMap<K2, V2> contents) {
    if (contents.size == 0) {
      return;
    }

    if (size == 0) {
      ensureCapacity(contents.size);
      Node<K2, V2> node = contents.first;
      while (node != null) {
        putNoEvictNoResize(node.key, node.keyHash, node.value);
        node = node.next;
      }
    } else {
      ensureCapacity(Math.max(size, contents.size));
      Node<K2, V2> node = contents.first;
      while (node != null) {
        put(node.key, node.keyHash, node.value);
        node = node.next;
      }
    }
    checkSelf();
  }

  V putDefault(K key, V defaultValue) {
    int keyHash = DictHash.hash(key);
    Node<K, V> node = getNode(key, keyHash);
    if (node != null) {
      return node.value;
    } else {
      putNoEvict(key, keyHash, defaultValue);
      return defaultValue;
    }
  }

  private void unlink(Node<K, V> node) {
    if (node.next != null) {
      node.next.prev = node.prev;
    }
    if (node.prev != null) {
      node.prev.next = node.next;
    }
    if (node == first) {
      first = node.next;
    }
    if (node == last) {
      last = node.prev;
    }
  }

  V remove(K key) {
    if (size == 0) {
      return null;
    }
    Node<K, V> removed = remove(key, DictHash.hash(key));
    return removed != null ? removed.value : null;
  }

  private Node<K, V> remove(K key, int keyHash) {
    Node<K, V> up = null;
    Node<K, V> node = table[keyHash & table.length - 1];
    while (node != null) {
      if (node.keyEqual(key, keyHash)) {
        if (up == null) {
          table[keyHash & table.length - 1] = node.down;
        } else {
          up.down = node.down;
        }
        unlink(node);
        --size;
        checkSelf();
        return node;
      }

      up = node;
      node = node.down;
    }
    checkSelf();
    return null;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    Node<K, V> node = first;
    while (node != null) {
      if (node != first) {
        sb.append(", ");
      }
      sb.append(node.key);
      sb.append("=");
      sb.append(node.value);
      node = node.next;
    }
    sb.append("}");
    return sb.toString();
  }

  private abstract static class IteratorBase<K, V, X> implements Iterator<X> {
    @Nullable Node<K, V> next;

    public IteratorBase(@Nullable Node<K, V> next) {
      this.next = next;
    }

    @Override
    public boolean hasNext() {
      return next != null;
    }

    protected Node<K, V> nextNode() {
      if (next == null) {
        throw new NoSuchElementException();
      }
      Node<K, V> result = next;
      next = result.next;
      return result;
    }
  }

  private static class KeysIterator<K, V> extends IteratorBase<K, V, K> {
    public KeysIterator(@Nullable Node<K, V> next) {
      super(next);
    }

    @Override
    public K next() {
      return nextNode().key;
    }
  }

  private static class ValuesIterator<K, V> extends IteratorBase<K, V, V> {
    public ValuesIterator(@Nullable Node<K, V> next) {
      super(next);
    }

    @Override
    public V next() {
      return nextNode().value;
    }
  }

  private static class EntriesIterator<K, V> extends IteratorBase<K, V, Map.Entry<K, V>> {
    public EntriesIterator(@Nullable Node<K, V> next) {
      super(next);
    }

    @Override
    public Map.Entry<K, V> next() {
      Node<K, V> node = nextNode();
      return new AbstractMap.SimpleEntry<K, V>(node.key, node.value) {
        @Override
        public V setValue(V value) {
          throw new UnsupportedOperationException();
        }
      };
    }
  }

  private abstract class SetBase<E> extends AbstractSet<E> {
    @Override
    public int size() {
      return size;
    }
  }

  private class EntrySet extends SetBase<Map.Entry<K, V>> {
    @Override
    public Iterator<Map.Entry<K, V>> iterator() {
      return new EntriesIterator<>(first);
    }
  }

  private class Values extends AbstractCollection<V> {
    @Override
    public Iterator<V> iterator() {
      return new ValuesIterator<>(first);
    }

    @Override
    public int size() {
      return size;
    }
  }

  private class KeySet extends SetBase<K> {
    @Override
    public Iterator<K> iterator() {
      return keysIterator();
    }
  }

  Set<Map.Entry<K, V>> entrySet() {
    return new EntrySet();
  }

  Collection<V> values() {
    return new Values();
  }

  Set<K> keySet() {
    return new KeySet();
  }

  Iterator<K> keysIterator() {
    return new KeysIterator<>(first);
  }

  Object[] keysToArray() {
    Node<K, V> node = this.first;
    if (node == null) {
      return ArraysForStarlark.EMPTY_OBJECT_ARRAY;
    }
    Object[] array = new Object[size];
    for (int i = 0; i != size; ++i) {
      array[i] = node.key;
      node = node.next;
    }
    return array;
  }

  Object[] valuesToArray() {
    Node<K, V> node = this.first;
    if (node == null) {
      return ArraysForStarlark.EMPTY_OBJECT_ARRAY;
    }
    Object[] array = new Object[size];
    for (int i = 0; i != size; ++i) {
      array[i] = node.value;
      node = node.next;
    }
    return array;
  }

  Object[] nodesToArray() {
    Node<K, V> node = this.first;
    if (node == null) {
      return ArraysForStarlark.EMPTY_OBJECT_ARRAY;
    }
    Object[] array = new Object[size];
    for (int i = 0; i != size; ++i) {
      array[i] = node;
      node = node.next;
    }
    return array;
  }

  @Override
  public int hashCode() {
    int h = 0;
    for (Map.Entry<K, V> kvEntry : entrySet()) {
      h += kvEntry.hashCode();
    }
    return h;
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean equals(Object obj) {
    if (!(obj instanceof DictMap<?, ?>)) {
      return false;
    }
    DictMap<K, V> that = (DictMap<K, V>) obj;
    if (this.size != that.size) {
      return false;
    }
    Node<K, V> node = first;
    while (node != null) {
      if (!that.containsEntry(node.key, node.keyHash, node.value)) {
        return false;
      }
      node = node.next;
    }
    return true;
  }

  private void checkSelf() {
    // uncomment to self-test
    // doCheckSelf();
  }

  /** Check all map invariants. */
  @VisibleForTesting
  void doCheckSelf() {
    HashSet<Object> nodesFromTable = new HashSet<>();
    for (int i = 0; i < table.length; i++) {
      Node<K, V> node = table[i];
      while (node != null) {
        Preconditions.checkState(node.keyHash == DictHash.hash(node.key));
        Preconditions.checkState(node.value != null);
        Preconditions.checkState((node.keyHash & table.length - 1) == i);
        Preconditions.checkState(nodesFromTable.add(node));
        node = node.down;
      }
    }
    Preconditions.checkState(size == nodesFromTable.size());

    Preconditions.checkState((size == 0) == (first == null));
    Preconditions.checkState((size == 0) == (last == null));

    Node<K, V> node = this.first;
    for (int i = 0; i != size; ++i) {
      if (i == 0) {
        Preconditions.checkState(node.prev == null);
      } else {
        Preconditions.checkState(node.prev.next == node);
      }
      if (i == size - 1) {
        Preconditions.checkState(node == last);
        Preconditions.checkState(node.next == null);
      } else {
        Preconditions.checkState(node.next.prev == node);
      }
      node = node.next;
    }
  }

  static <K> DictMap<K, NoneType> makeSetFromUniqueKeys(K[] keys, int[] keyDictHashes) {
    Preconditions.checkArgument(keys.length == keyDictHashes.length);
    DictMap<K, NoneType> map = new DictMap<>(keys.length);
    for (int i = 0; i < keys.length; i++) {
      K key = keys[i];
      int keyHash = keyDictHashes[i];
      map.putNoEvictNoResize(key, keyHash, NoneType.NONE);
    }
    return map;
  }
}
