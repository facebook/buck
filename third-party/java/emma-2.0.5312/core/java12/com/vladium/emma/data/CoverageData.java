/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CoverageData.java,v 1.1.1.1 2004/05/09 16:57:31 vlad_r Exp $
 */
package com.vladium.emma.data;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
final class CoverageData implements ICoverageData, Cloneable
{
    // public: ................................................................
    
    // TODO: duplicate issue
       
    public Object lock ()
    {
        return m_coverageMap;
    }
    
    public ICoverageData shallowCopy ()
    {
        final CoverageData _clone;
        try
        {
            _clone = (CoverageData) super.clone ();
        }
        catch (CloneNotSupportedException cnse)
        {
            throw new Error (cnse.toString ());
        }
        
        final HashMap _coverageMap;
        
        synchronized (lock ())
        {
            _coverageMap = (HashMap) m_coverageMap.clone ();
        }
        
        _clone.m_coverageMap = _coverageMap;
        
        return _clone;
    }
    
    public int size ()
    {
        return m_coverageMap.size (); 
    }

    public DataHolder getCoverage (final ClassDescriptor cls)
    {
        if (cls == null) throw new IllegalArgumentException ("null input: cls");
        
        return (DataHolder) m_coverageMap.get (cls.getClassVMName ());
    }
    
    public void addClass (final boolean [][] coverage, final String classVMName, final long stamp)
    {
        m_coverageMap.put (classVMName, new DataHolder (coverage, stamp));
    }
    
    // IMergeable:
    
    public boolean isEmpty ()
    {
        return m_coverageMap.isEmpty ();
    }

    /*
     * This method is not MT-safe wrt addClass() etc.
     * 
     * note: rhs entries override current entries if they have different stamps;
     * otherwise, the data is merged 
     */    
    public IMergeable merge (final IMergeable rhs)
    {
        if ((rhs == null) || rhs.isEmpty () || (rhs == this))
            return this;
        else
        {
            final CoverageData rhscdata = (CoverageData) rhs; // TODO: redesign so that the cast is not necessary
            final Map rhscoverageData = rhscdata.m_coverageMap;
            
            for (Iterator entries = rhscoverageData.entrySet ().iterator (); entries.hasNext (); )
            {
                final Map.Entry entry = (Map.Entry) entries.next ();
                final String classVMName = (String) entry.getKey ();
                
                final DataHolder rhsdata = (DataHolder) entry.getValue ();
                // [assertion: rhsdata != null]
                
                final DataHolder data = (DataHolder) m_coverageMap.get (classVMName);
                
                if (data == null)
                    m_coverageMap.put (classVMName, rhsdata);
                else
                {
                    if (rhsdata.m_stamp != data.m_stamp)
                        m_coverageMap.put (classVMName, rhsdata);
                    else // merge two runtime profiles
                    {
                        final boolean [][] rhscoverage = rhsdata.m_coverage;
                        final boolean [][] coverage = data.m_coverage;
                        
                        // [assertion: both coverage and rhscoverage aren't null]
                    
                        if ($assert.ENABLED) $assert.ASSERT (coverage.length == rhscoverage.length, "coverage.length [" + coverage.length + "] != rhscoverage.length [" + rhscoverage.length + "]");
                        for (int m = 0, mLimit = coverage.length; m < mLimit; ++ m)
                        {
                            final boolean [] rhsmcoverage = rhscoverage [m];
                            final boolean [] mcoverage = coverage [m];
                            
                            if (mcoverage == null)
                            {
                                if ($assert.ENABLED) $assert.ASSERT (rhsmcoverage == null, "mcoverage == null but rhsmcoverage != null");
                                
                                // [nothing to merge]
                            }
                            else
                            {
                                if ($assert.ENABLED) $assert.ASSERT (rhsmcoverage != null, "mcoverage != null but rhsmcoverage == null");
                                if ($assert.ENABLED) $assert.ASSERT (mcoverage.length == rhsmcoverage.length, "mcoverage.length [" + mcoverage.length + "] != rhsmcoverage.length [" + rhsmcoverage.length + "]");
                                
                                for (int b = 0, bLimit = mcoverage.length; b < bLimit; ++ b)
                                {
                                    if (rhsmcoverage [b]) mcoverage [b] = true;
                                }
                            }
                        }
                    }
                }
            }
                
            return this;
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    
    CoverageData ()
    {
        m_coverageMap = new HashMap ();
    }
    
    
    static CoverageData readExternal (final DataInput in)
        throws IOException
    {
        final int size = in.readInt ();
        final HashMap coverageMap = new HashMap (size);
        
        for (int i = 0; i < size; ++ i)
        {
            final String classVMName = in.readUTF ();
            final long stamp = in.readLong ();
            
            final int length = in.readInt ();
            final boolean [][] coverage = new boolean [length][];
            for (int c = 0; c < length; ++ c) 
            {
                coverage [c] = DataFactory.readBooleanArray (in);
            }
            
            coverageMap.put (classVMName, new DataHolder (coverage, stamp));
        }
        
        return new CoverageData (coverageMap);
    }
    
    static void writeExternal (final CoverageData cdata, final DataOutput out)
        throws IOException
    {
        final Map coverageMap = cdata.m_coverageMap;
        
        final int size = coverageMap.size ();
        out.writeInt (size);
        
        final Iterator entries = coverageMap.entrySet ().iterator ();
        for (int i = 0; i < size; ++ i)
        {
            final Map.Entry entry = (Map.Entry) entries.next ();
            
            final String classVMName = (String) entry.getKey ();
            final DataHolder data = (DataHolder) entry.getValue ();
            
            final boolean [][] coverage = data.m_coverage;
            
            out.writeUTF (classVMName);
            out.writeLong (data.m_stamp);
            
            final int length = coverage.length;
            out.writeInt (length);
            for (int c = 0; c < length; ++ c)
            {
                DataFactory.writeBooleanArray (coverage [c], out);
            }
        }
    }

    // private: ...............................................................
    
    
    private CoverageData (final HashMap coverageMap)
    {
        if ($assert.ENABLED) $assert.ASSERT (coverageMap != null, "coverageMap is null");
        m_coverageMap = coverageMap;
    }
    
    
    private /*final*/ HashMap /* String(classVMName) -> DataHolder */ m_coverageMap; // never null

} // end of class
// ----------------------------------------------------------------------------