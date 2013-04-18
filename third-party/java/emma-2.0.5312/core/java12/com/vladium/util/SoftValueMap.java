/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: SoftValueMap.java,v 1.1.1.1 2004/05/09 16:57:55 vlad_r Exp $
 */
package com.vladium.util;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

// ----------------------------------------------------------------------------
/**
 * MT-safety: an instance of this class is <I>not</I> safe for access from
 * multiple concurrent threads [even if access is done by a single thread at a
 * time]. The caller is expected to synchronize externally on an instance [the
 * implementation does not do internal synchronization for the sake of efficiency].
 * java.util.ConcurrentModificationException is not supported either.
 *
 * @author (C) 2002, Vlad Roubtsov
 */
public
final class SoftValueMap implements Map
{
    // public: ................................................................

    // TODO: for caching, does clearing of entries make sense? only to save
    // entry memory -- which does not make sense if the set of key values is not
    // growing over time... on the other hand, if the key set is unbounded,
    // entry clearing is needed so that the hash table does not get polluted with
    // empty-valued entries 
    // TODO: provide mode that disables entry clearing 
    // TODO: add shrinking rehashes (is it worth it?)

    /**
     * Equivalent to <CODE>SoftValueMap(1, 1)</CODE>.
     */
    public SoftValueMap ()
    {
        this (1, 1);
    }
    
    /**
     * Equivalent to <CODE>SoftValueMap(11, 0.75F, getClearCheckFrequency, putClearCheckFrequency)</CODE>.
     */
    public SoftValueMap (final int readClearCheckFrequency, final int writeClearCheckFrequency)
    {
        this (11, 0.75F, readClearCheckFrequency, writeClearCheckFrequency);
    }
    
    /**
     * Constructs a SoftValueMap with specified initial capacity, load factor,
     * and cleared value removal frequencies.
     *
     * @param initialCapacity initial number of hash buckets in the table
     * [may not be negative, 0 is equivalent to 1].
     * @param loadFactor the load factor to use to determine rehashing points
     * [must be in (0.0, 1.0] range].
     * @param readClearCheckFrequency specifies that every readClearCheckFrequency
     * {@link #get} should check for and remove all mappings whose soft values
     * have been cleared by the garbage collector [may not be less than 1].
     * @param writeClearCheckFrequency specifies that every writeClearCheckFrequency
     * {@link #put} should check for and remove all mappings whose soft values
     * have been cleared by the garbage collector [may not be less than 1].
     */
    public SoftValueMap (int initialCapacity, final float loadFactor, final int readClearCheckFrequency, final int writeClearCheckFrequency)
    {
        if (initialCapacity < 0)
            throw new IllegalArgumentException ("negative input: initialCapacity [" + initialCapacity + "]");
        if ((loadFactor <= 0.0) || (loadFactor >= 1.0 + 1.0E-6))
            throw new IllegalArgumentException ("loadFactor not in (0.0, 1.0] range: " + loadFactor);
        if (readClearCheckFrequency < 1)
            throw new IllegalArgumentException ("readClearCheckFrequency not in [1, +inf) range: " + readClearCheckFrequency);
        if (writeClearCheckFrequency < 1)
            throw new IllegalArgumentException ("writeClearCheckFrequency not in [1, +inf) range: " + writeClearCheckFrequency);
        
        if (initialCapacity == 0) initialCapacity = 1;
        
        m_valueReferenceQueue = new ReferenceQueue ();
        
        m_loadFactor = loadFactor;
        m_sizeThreshold = (int) (initialCapacity * loadFactor);
        
        m_readClearCheckFrequency = readClearCheckFrequency;
        m_writeClearCheckFrequency = writeClearCheckFrequency;
        
        m_buckets = new SoftEntry [initialCapacity];
    }
    
    
    // unsupported operations:
        
    public boolean equals (final Object rhs)
    {
        throw new UnsupportedOperationException ("not implemented: equals");
    }
    
    public int hashCode ()
    {
        throw new UnsupportedOperationException ("not implemented: hashCode");
    }
    
    
    /**
     * Overrides Object.toString() for debug purposes.
     */
    public String toString ()
    {
        final StringBuffer s = new StringBuffer ();
        debugDump (s);
        
        return s.toString ();
    }
    
    
    /**
     * Returns the number of key-value mappings in this map. Some of the values
     * may have been cleared already but not removed from the table.<P>
     *
     * <B>NOTE:</B> in contrast with the java.util.WeakHashMap implementation,
     * this is a constant time operation.
     */
    public int size ()
    {
        return m_size;
    }
    
    /**
     * Returns 'false' is this map contains key-value mappings (even if some of
     * the values may have been cleared already but not removed from the table).<P>
     *
     * <B>NOTE:</B> in contrast with the java.util.WeakHashMap implementation,
     * this is a constant time operation.
     */
    public boolean isEmpty ()
    {
        return m_size == 0;
    }
    
    /**
     * Returns the value that is mapped to a given 'key'. Returns
     * null if (a) this key has never been mapped or (b) a previously mapped
     * value has been cleared by the garbage collector and removed from the table.
     *
     * @param key mapping key [may not be null].
     *
     * @return Object value mapping for 'key' [can be null].
     */
    public Object get (final Object key)
    {
        if (key == null) throw new IllegalArgumentException ("null input: key");
        
        if ((++ m_readAccessCount % m_readClearCheckFrequency) == 0) removeClearedValues ();
        
        // index into the corresponding hash bucket:
        final int keyHashCode = key.hashCode ();
        final SoftEntry [] buckets = m_buckets;
        final int bucketIndex = (keyHashCode & 0x7FFFFFFF) % buckets.length;
        
        Object result = null; 
        
        // traverse the singly-linked list of entries in the bucket:
        for (SoftEntry entry = buckets [bucketIndex]; entry != null; entry = entry.m_next)
        {
            final Object entryKey = entry.m_key;

            if (IDENTITY_OPTIMIZATION)
            {
                // note: this uses an early identity comparison opimization, making this a bit
                // faster for table keys that do not override equals() [Thread, etc]
                if ((key == entryKey) || ((keyHashCode == entryKey.hashCode ()) && key.equals (entryKey)))
                {
                    final Reference ref = entry.m_softValue;
                    result = ref.get (); // may return null to the caller
                    
                    // [see comment for ENQUEUE_FOUND_CLEARED_ENTRIES]
                    if (ENQUEUE_FOUND_CLEARED_ENTRIES && (result == null))
                    {
                        ref.enqueue ();
                    }
                    
                    return result;
                }
            }
            else
            {
                if ((keyHashCode == entryKey.hashCode ()) && key.equals (entryKey))
                {
                    final Reference ref = entry.m_softValue;
                    result = ref.get (); // may return null to the caller
                    
                    // [see comment for ENQUEUE_FOUND_CLEARED_ENTRIES]
                    if (ENQUEUE_FOUND_CLEARED_ENTRIES && (result == null))
                    {
                        ref.enqueue ();
                    }
                    
                    return result;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Updates the table to map 'key' to 'value'. Any existing mapping is overwritten.
     *
     * @param key mapping key [may not be null].
     * @param value mapping value [may not be null].
     *
     * @return Object previous value mapping for 'key' [null if no previous mapping
     * existed or its value has been cleared by the garbage collector and removed from the table].
     */
    public Object put (final Object key, final Object value)
    {
        if (key == null) throw new IllegalArgumentException ("null input: key");
        if (value == null) throw new IllegalArgumentException ("null input: value");
        
        if ((++ m_writeAccessCount % m_writeClearCheckFrequency) == 0) removeClearedValues ();
            
        SoftEntry currentKeyEntry = null;
        
        // detect if 'key' is already in the table [in which case, set 'currentKeyEntry' to point to its entry]:
        
        // index into the corresponding hash bucket:
        final int keyHashCode = key.hashCode ();
        SoftEntry [] buckets = m_buckets;
        int bucketIndex = (keyHashCode & 0x7FFFFFFF) % buckets.length;
        
        // traverse the singly-linked list of entries in the bucket:
        for (SoftEntry entry = buckets [bucketIndex]; entry != null; entry = entry.m_next)
        {
            final Object entryKey = entry.m_key;
            
            if (IDENTITY_OPTIMIZATION)
            {
                // note: this uses an early identity comparison opimization, making this a bit
                // faster for table keys that do not override equals() [Thread, etc]
                if ((key == entryKey) || ((keyHashCode == entryKey.hashCode ()) && key.equals (entryKey)))
                {
                    currentKeyEntry = entry;
                    break;
                }
            }
            else
            {
                if ((keyHashCode == entryKey.hashCode ()) && key.equals (entryKey))
                {
                    currentKeyEntry = entry;
                    break;
                }
            }
        }
        
        if (currentKeyEntry != null)
        {
            // replace the current value:
            
            final IndexedSoftReference ref = currentKeyEntry.m_softValue;
            final Object currentKeyValue = ref.get (); // can be null already [no need to work around the get() bug, though]
            
            if (currentKeyValue == null) ref.m_bucketIndex = -1; // disable removal by removeClearedValues() [need to do this because of the identity comparison there]
            currentKeyEntry.m_softValue = new IndexedSoftReference (value, m_valueReferenceQueue, bucketIndex);
            
            return currentKeyValue; // may return null to the caller
        }
        else
        {
            // add a new entry:
            
            if (m_size >= m_sizeThreshold) rehash ();
            
            // recompute the hash bucket index:
            buckets = m_buckets;
            bucketIndex = (keyHashCode & 0x7FFFFFFF) % buckets.length;
            final SoftEntry bucketListHead = buckets [bucketIndex];
            final SoftEntry newEntry = new SoftEntry (m_valueReferenceQueue, key, value, bucketListHead, bucketIndex);
            buckets [bucketIndex] = newEntry;
            
            ++ m_size;
            
            return null;
        }
    }
    
    public Object remove (final Object key)
    {
        if (key == null) throw new IllegalArgumentException ("null input: key");
        
        if ((++ m_writeAccessCount % m_writeClearCheckFrequency) == 0) removeClearedValues ();

        // index into the corresponding hash bucket:
        final int keyHashCode = key.hashCode ();
        final SoftEntry [] buckets = m_buckets;
        final int bucketIndex = (keyHashCode & 0x7FFFFFFF) % buckets.length;
        
        Object result = null;

        // traverse the singly-linked list of entries in the bucket:
        for (SoftEntry entry = buckets [bucketIndex], prev = null; entry != null; prev = entry, entry = entry.m_next)
        {
            final Object entryKey = entry.m_key;
            
            if ((IDENTITY_OPTIMIZATION && (entryKey == key)) || ((keyHashCode == entryKey.hashCode ()) && key.equals (entryKey)))
            {
                if (prev == null) // head of the list
                {
                    buckets [bucketIndex] = entry.m_next;
                }
                else
                {
                    prev.m_next = entry.m_next;
                }
                
                final IndexedSoftReference ref = entry.m_softValue; 
                result = ref.get (); // can be null already [however, no need to work around 4485942]
                
                // [regardless of whether the value has been enqueued or not, disable its processing by removeClearedValues() since the entire entry is removed here]
                ref.m_bucketIndex = -1;
                
                // help GC:
                entry.m_softValue = null;
                entry.m_key = null;
                entry.m_next = null;
                entry = null;
            
                -- m_size;
                break;
            }
        }
        
        return result;
    }

    
    public void clear ()
    {
        final SoftEntry [] buckets = m_buckets;
        
        for (int b = 0, bLimit = buckets.length; b < bLimit; ++ b)
        {
            for (SoftEntry entry = buckets [b]; entry != null; )
            {
                final SoftEntry next = entry.m_next; // remember next pointer because we are going to reuse this entry

                // [regardless of whether the value has been enqueued or not, disable its processing by removeClearedValues()]
                entry.m_softValue.m_bucketIndex = -1;
                
                // help GC:
                entry.m_softValue = null;
                entry.m_next = null;
                entry.m_key = null;
                
                entry = next;
            }
            
            buckets [b] = null;
        }
        
        m_size = 0;
        m_readAccessCount = 0;
        m_writeAccessCount = 0;
    }


    // unsupported operations:
    
    public boolean containsKey (final Object key)
    {
        throw new UnsupportedOperationException ("not implemented: containsKey");
    }
    
    public boolean containsValue (final Object value)
    {
        throw new UnsupportedOperationException ("not implemented: containsValue");
    }
        
    public void putAll (final Map map)
    {
        throw new UnsupportedOperationException ("not implemented: putAll");
    }
    
    public Set keySet ()
    {
        throw new UnsupportedOperationException ("not implemented: keySet");
    }
    
    public Set entrySet ()
    {
        throw new UnsupportedOperationException ("not implemented: entrySet");
    }

    public Collection values ()
    {
        throw new UnsupportedOperationException ("not implemented: values");
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    
    void debugDump (final StringBuffer out)
    {
        if (out != null)
        {
            out.append (getClass ().getName ().concat ("@").concat (Integer.toHexString (System.identityHashCode (this)))); out.append (EOL);
            out.append ("size = " + m_size + ", bucket table size = " + m_buckets.length + ", load factor = " + m_loadFactor + EOL);
            out.append ("size threshold = " + m_sizeThreshold + ", get clear frequency = " + m_readClearCheckFrequency + ", put clear frequency = " + m_writeClearCheckFrequency + EOL);
            out.append ("get count: " + m_readAccessCount + ", put count: " + m_writeAccessCount + EOL);
        }
    }

    // private: ...............................................................


    /**
     * An extension of WeakReference that can store an index of the bucket it
     * is associated with.
     */
    static class IndexedSoftReference extends SoftReference
    {
        IndexedSoftReference (final Object referent, ReferenceQueue queue, final int bucketIndex)
        {
            super (referent, queue);
            
            m_bucketIndex = bucketIndex;
        }
        
        int m_bucketIndex;
        
    } // end of nested class
    
    
    /**
     * The structure used for chaining colliding keys.
     */
    static class SoftEntry
    {
        SoftEntry (final ReferenceQueue valueReferenceQueue, final Object key, Object value, final SoftEntry next, final int bucketIndex)
        {
            m_key = key;
            
            m_softValue = new IndexedSoftReference (value, valueReferenceQueue, bucketIndex); // ... do not retain a strong reference to the value
            value = null;
            
            m_next = next;
        }
        
        IndexedSoftReference m_softValue; // soft reference to the value [never null]
        Object m_key;  // strong reference to the key [never null]
        
        SoftEntry m_next; // singly-linked list link
        
    } // end of nested class
    

    /**
     * Re-hashes the table into a new array of buckets. During the process
     * cleared value entries are discarded, making for another efficient cleared
     * value removal method.
     */
    private void rehash ()
    {
        // TODO: it is possible to run this method twice, first time using the 2*k+1 prime sequencer for newBucketCount
        // and then with that value reduced to actually shrink capacity. As it is right now, the bucket table can
        // only grow in size
        
        final SoftEntry [] buckets = m_buckets;
        
        final int newBucketCount = (m_buckets.length << 1) + 1;
        final SoftEntry [] newBuckets = new SoftEntry [newBucketCount];
        
        int newSize = 0;
        
        // rehash all entry chains in every bucket:
        for (int b = 0, bLimit = buckets.length; b < bLimit; ++ b)
        {
            for (SoftEntry entry = buckets [b]; entry != null; )
            {
                final SoftEntry next = entry.m_next; // remember next pointer because we are going to reuse this entry
                
                IndexedSoftReference ref = entry.m_softValue; // get the soft value reference
                                
                Object entryValue = ref.get (); // convert the soft reference to a local strong one
            
                // skip entries whose keys have been cleared: this also saves on future removeClearedValues() work
                if (entryValue != null)
                {
                    // [assertion: 'softValue' couldn't have been enqueued already and can't be enqueued until strong reference in 'entryKey' is nulled out]
                    
                    // index into the corresponding new hash bucket:
                    final int entryKeyHashCode = entry.m_key.hashCode ();
                    final int newBucketIndex = (entryKeyHashCode & 0x7FFFFFFF) % newBucketCount;
                    
                    final SoftEntry bucketListHead = newBuckets [newBucketIndex];
                    entry.m_next = bucketListHead;
                    newBuckets [newBucketIndex] = entry;
                    
                    // adjust bucket index:
                    ref.m_bucketIndex = newBucketIndex;
            
                    ++ newSize;
                    
                    entryValue = null;
                }
                else
                {
                    // ['softValue' may or may not have been enqueued already]
                    
                    // adjust bucket index:
                    // [regardless of whether 'softValue' has been enqueued or not, disable its removal by removeClearedValues() since the buckets get restructured]
                    ref.m_bucketIndex = -1;
                }
                
                entry = next;
            }
        }
        
        if (DEBUG)
        {
            if (m_size > newSize) System.out.println ("DEBUG: rehash() cleared " + (m_size - newSize) + " values, new size = " + newSize);
        }
        
        m_size = newSize;
        m_sizeThreshold = (int) (newBucketCount * m_loadFactor);
        m_buckets = newBuckets;
    }
    

    /**
     * Removes all entries whose soft values have been cleared _and_ enqueued.
     * See comments below for why this is safe wrt to rehash().
     */
    private void removeClearedValues ()
    {
        int count = 0;
        
next:   for (Reference _ref; (_ref = m_valueReferenceQueue.poll ()) != null; )
        {
            // remove entry containing '_ref' using its bucket index and identity comparison:
            
            // index into the corresponding hash bucket:
            final int bucketIndex = ((IndexedSoftReference) _ref).m_bucketIndex;
            
            if (bucketIndex >= 0) // skip keys that were already removed by rehash()
            {
                // [assertion: this reference was not cleared when the last rehash() ran and so its m_bucketIndex is correct]
                
                // traverse the singly-linked list of entries in the bucket:
                for (SoftEntry entry = m_buckets [bucketIndex], prev = null; entry != null; prev = entry, entry = entry.m_next)
                {
                    if (entry.m_softValue == _ref)
                    {
                        if (prev == null) // head of the list
                        {
                            m_buckets [bucketIndex] = entry.m_next;
                        }
                        else
                        {
                            prev.m_next = entry.m_next;
                        }
                    
                        entry.m_softValue = null;
                        entry.m_key = null;
                        entry.m_next = null;
                        entry = null;
                    
                        -- m_size;
                        
                        if (DEBUG) ++ count;
                        continue next;
                    }
                }
                
                // no match found this can happen if a soft value got replaced by a put
                
                final StringBuffer msg = new StringBuffer ("removeClearedValues(): soft reference [" + _ref + "] did not match within bucket #" + bucketIndex + EOL);
                debugDump (msg);
            
                throw new Error (msg.toString ());
            }
            // else: it has already been removed by rehash() or other methods
        }
        
        if (DEBUG)
        {
            if (count > 0) System.out.println ("DEBUG: removeClearedValues() cleared " + count + " keys, new size = " + m_size);
        }
    }
    
    
    private final ReferenceQueue m_valueReferenceQueue; // reference queue for all references used by SoftEntry objects used by this table
    private final float m_loadFactor; // determines the setting of m_sizeThreshold
    private final int m_readClearCheckFrequency, m_writeClearCheckFrequency; // parameters determining frequency of running removeClearedKeys() by get() and put()/remove(), respectively
    
    private SoftEntry [] m_buckets; // table of buckets
    private int m_size; // number of values in the table, not cleared as of last check
    private int m_sizeThreshold; // size threshold for rehashing
    private int m_readAccessCount, m_writeAccessCount;
    
    private static final String EOL = System.getProperty ("line.separator", "\n");
    
    private static final boolean IDENTITY_OPTIMIZATION          = true;
    
    // setting this to 'true' is an optimization and a workaround for bug 4485942:
    private static final boolean ENQUEUE_FOUND_CLEARED_ENTRIES  = true; 
    
    private static final boolean DEBUG = false;

} // end of class
// ----------------------------------------------------------------------------
