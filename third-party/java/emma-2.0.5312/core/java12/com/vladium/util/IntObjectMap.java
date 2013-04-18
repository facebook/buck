/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IntObjectMap.java,v 1.1.1.1 2004/05/09 16:57:53 vlad_r Exp $
 */
package com.vladium.util;

import java.io.Serializable;

// ----------------------------------------------------------------------------
/**
 *
 * MT-safety: an instance of this class is <I>not</I> safe for access from
 * multiple concurrent threads [even if access is done by a single thread at a
 * time]. The caller is expected to synchronize externally on an instance [the
 * implementation does not do internal synchronization for the sake of efficiency].
 * java.util.ConcurrentModificationException is not supported either.
 *
 * @author Vlad Roubtsov, (C) 2001
 */
public
final class IntObjectMap implements Serializable
{
    // public: ................................................................

    /**
     * Equivalent to <CODE>IntObjectMap(11, 0.75F)</CODE>.
     */
    public IntObjectMap ()
    {
        this (11, 0.75F);
    }
    
    /**
     * Equivalent to <CODE>IntObjectMap(capacity, 0.75F)</CODE>.
     */
    public IntObjectMap (final int initialCapacity)
    {
        this (initialCapacity, 0.75F);
    }
    
    /**
     * Constructs an IntObjectMap with specified initial capacity and load factor.
     *
     * @param initialCapacity initial number of hash buckets in the table [may not be negative, 0 is equivalent to 1].
     * @param loadFactor the load factor to use to determine rehashing points [must be in (0.0, 1.0] range].
     */
    public IntObjectMap (int initialCapacity, final float loadFactor)
    {
        if (initialCapacity < 0) throw new IllegalArgumentException ("negative input: initialCapacity [" + initialCapacity + "]");
        if ((loadFactor <= 0.0) || (loadFactor >= 1.0 + 1.0E-6))
            throw new IllegalArgumentException ("loadFactor not in (0.0, 1.0] range: " + loadFactor);
        
        if (initialCapacity == 0) initialCapacity = 1;
        
        m_loadFactor = loadFactor > 1.0 ? 1.0F : loadFactor;        
        m_sizeThreshold = (int) (initialCapacity * loadFactor);
        m_buckets = new Entry [initialCapacity];
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
     * Returns the number of key-value mappings in this map.
     */
    public int size ()
    {
        return m_size;
    }

    public boolean contains (final int key)
    {
        // index into the corresponding hash bucket:
        final Entry [] buckets = m_buckets;
        final int bucketIndex = (key & 0x7FFFFFFF) % buckets.length;
        
        // traverse the singly-linked list of entries in the bucket:
        for (Entry entry = buckets [bucketIndex]; entry != null; entry = entry.m_next)
        {
            if (key == entry.m_key)
                return true;
        }
        
        return false;
    }
    
    /**
     * Returns the value that is mapped to a given 'key'. Returns
     * null if (a) this key has never been mapped or (b) it has been
     * mapped to a null value.
     *
     * @param key mapping key
     *
     * @return Object value mapping for 'key' [can be null].
     */
    public Object get (final int key)
    {
        // index into the corresponding hash bucket:
        final Entry [] buckets = m_buckets;
        final int bucketIndex = (key & 0x7FFFFFFF) % buckets.length;
        
        // traverse the singly-linked list of entries in the bucket:
        for (Entry entry = buckets [bucketIndex]; entry != null; entry = entry.m_next)
        {
            if (key == entry.m_key)
                return entry.m_value;
        }
        
        return null;
    }
    
    public int [] keys ()
    {
        if (m_size == 0)
            return IConstants.EMPTY_INT_ARRAY;
        else
        {
            final int [] result = new int [m_size];
            int scan = 0;
            
            for (int b = 0; b < m_buckets.length; ++ b)
            {
                for (Entry entry = m_buckets [b]; entry != null; entry = entry.m_next)
                {
                    result [scan ++] = entry.m_key;
                }
            }
            
            return result;
        }
    }
    
    /**
     * Updates the table to map 'key' to 'value'. Any existing mapping is overwritten.
     *
     * @param key mapping key
     * @param value mapping value [can be null].
     *
     * @return Object previous value mapping for 'key' [can be null]
     */
    public Object put (final int key, final Object value)
    {
        Entry currentKeyEntry = null;
        
        // detect if 'key' is already in the table [in which case, set 'currentKeyEntry' to point to its entry]:
        
        // index into the corresponding hash bucket:
        int bucketIndex = (key & 0x7FFFFFFF) % m_buckets.length;
        
        // traverse the singly-linked list of entries in the bucket:
        Entry [] buckets = m_buckets;
        for (Entry entry = buckets [bucketIndex]; entry != null; entry = entry.m_next)
        {
            if (key == entry.m_key)
            {
                currentKeyEntry = entry;
                break;
            }
        }
        
        if (currentKeyEntry != null)
        {
            // replace the current value:
                
            final Object currentKeyValue = currentKeyEntry.m_value;
            currentKeyEntry.m_value = value;
            
            return currentKeyValue;
        }
        else
        {
            // add a new entry:
            
            if (m_size >= m_sizeThreshold) rehash ();
            
            buckets = m_buckets;
            bucketIndex = (key & 0x7FFFFFFF) % buckets.length;
            final Entry bucketListHead = buckets [bucketIndex];
            final Entry newEntry = new Entry (key, value, bucketListHead);
            buckets [bucketIndex] = newEntry;
            
            ++ m_size;
            
            return null;
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    
    void debugDump (final StringBuffer out)
    {
        if (out != null)
        {
            out.append (super.toString ()); out.append (EOL);
            out.append ("size = " + m_size + ", bucket table size = " + m_buckets.length + ", load factor = " + m_loadFactor + EOL);
            out.append ("size threshold = " + m_sizeThreshold + EOL);
        }
    }

    // private: ...............................................................

    
    /**
     * The structure used for chaining colliding keys.
     */
    private static final class Entry implements Serializable
    {
        Entry (final int key, final Object value, final Entry next)
        {
            m_key = key; 
            m_value = value;
            m_next = next;
        }
        
        Object m_value;           // reference to the value [never null]
        final int m_key;
        
        Entry m_next; // singly-linked list link
        
    } // end of nested class
    

    /**
     * Re-hashes the table into a new array of buckets.
     */
    private void rehash ()
    {
        // TODO: it is possible to run this method twice, first time using the 2*k+1 prime sequencer for newBucketCount
        // and then with that value reduced to actually shrink capacity. As it is right now, the bucket table can
        // only grow in size
        
        final Entry [] buckets = m_buckets;
        
        final int newBucketCount = (m_buckets.length << 1) + 1;
        final Entry [] newBuckets = new Entry [newBucketCount];

        // rehash all entry chains in every bucket:
        for (int b = 0; b < buckets.length; ++ b)
        {
            for (Entry entry = buckets [b]; entry != null; )
            {
                final Entry next = entry.m_next; // remember next pointer because we are going to reuse this entry
                final int entryKey = entry.m_key;
            
                // index into the corresponding new hash bucket:
                final int newBucketIndex = (entryKey & 0x7FFFFFFF) % newBucketCount;
                
                final Entry bucketListHead = newBuckets [newBucketIndex];
                entry.m_next = bucketListHead;
                newBuckets [newBucketIndex] = entry;                                
                
                entry = next;
            }
        }
        

        m_sizeThreshold = (int) (newBucketCount * m_loadFactor);
        m_buckets = newBuckets;
    }
    
    
    private final float m_loadFactor; // determines the setting of m_sizeThreshold
    
    private Entry [] m_buckets; // table of buckets
    private int m_size; // number of keys in the table, not cleared as of last check
    private int m_sizeThreshold; // size threshold for rehashing
        
    private static final String EOL = System.getProperty ("line.separator", "\n");
    
} // end of class
// ----------------------------------------------------------------------------

