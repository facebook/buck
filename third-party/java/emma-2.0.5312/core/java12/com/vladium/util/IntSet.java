/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IntSet.java,v 1.1.1.1 2004/05/09 16:57:53 vlad_r Exp $
 */
package com.vladium.util;

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
final class IntSet
{
    // public: ................................................................

    /**
     * Equivalent to <CODE>IntSet(11, 0.75F)</CODE>.
     */
    public IntSet ()
    {
        this (11, 0.75F);
    }
    
    /**
     * Equivalent to <CODE>IntSet(capacity, 0.75F)</CODE>.
     */
    public IntSet (final int initialCapacity)
    {
        this (initialCapacity, 0.75F);
    }
    
    /**
     * Constructs an IntSet with specified initial capacity and load factor.
     *
     * @param initialCapacity initial number of hash buckets in the table [may not be negative, 0 is equivalent to 1].
     * @param loadFactor the load factor to use to determine rehashing points [must be in (0.0, 1.0] range].
     */
    public IntSet (int initialCapacity, final float loadFactor)
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
    
    public int [] values ()
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
    
    public void values (final int [] target, final int offset)
    {
        if (m_size != 0)
        {
            int scan = offset;
            
            for (int b = 0; b < m_buckets.length; ++ b)
            {
                for (Entry entry = m_buckets [b]; entry != null; entry = entry.m_next)
                {
                    target [scan ++] = entry.m_key;
                }
            }
        }
    }
    
    public boolean add (final int key)
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
        
        if (currentKeyEntry == null)
        {
            // add a new entry:
            
            if (m_size >= m_sizeThreshold) rehash ();
            
            buckets = m_buckets;
            bucketIndex = (key & 0x7FFFFFFF) % buckets.length;
            final Entry bucketListHead = buckets [bucketIndex];
            final Entry newEntry = new Entry (key, bucketListHead);
            buckets [bucketIndex] = newEntry;
            
            ++ m_size;
            
            return true;
        }
        else
            return false;
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
    private static final class Entry
    {
        Entry (final int key, final Entry next)
        {
            m_key = key; 
            m_next = next;
        }
        
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

