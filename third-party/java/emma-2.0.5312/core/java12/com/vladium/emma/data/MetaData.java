/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: MetaData.java,v 1.1.1.1.2.2 2004/07/16 23:32:29 vlad_r Exp $
 */
package com.vladium.emma.data;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

import com.vladium.logging.Logger;
import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/*
 * Average mem size/class entry: 6166 bytes [1.4.1, rt.jar], 5764 bytes [1.3.1, rt.jar]
 */
/**
 * @author Vlad Roubtsov, (C) 2003
 */
final class MetaData implements IMetaData, Cloneable
{
    // public: ................................................................
    
    // TODO: MT-safety model
    
    // TODO: no duplicate detection is done here at the moment
    // [may require supporting fast lookup for already added descriptors]
    
    public IMetaData shallowCopy ()
    {
        final MetaData _clone;
        try
        {
            _clone = (MetaData) super.clone ();
        }
        catch (CloneNotSupportedException cnse)
        {
            throw new Error (cnse.toString ());
        }
        
        final HashMap _classMap;
        
        synchronized (lock ())
        {
            _classMap = (HashMap) m_classMap.clone ();
        }
        
        // [m_packagesWarned is not cloned by design]
        
        _clone.m_classMap = _classMap;
        
        return _clone;  
    }
    
    public CoverageOptions getOptions ()
    {
        return m_options;
    }
    
    public int size ()
    {
        return m_classMap.size (); 
    }
    
    public boolean hasSrcFileData ()
    {
        return m_hasSrcFileInfo;
    }
    
    public boolean hasLineNumberData ()
    {
        return m_hasLineNumberInfo;
    }
    
    public Iterator iterator ()
    {
        return m_classMap.values ().iterator ();
    }
    
//    public boolean hasDescriptor (final ClassDescriptor cls)
//    {
//        if ($assert.ENABLED) $assert.ASSERT (cls != null, "cls is null");
//        
//        return m_classes.contains (cls);
//    }

    public boolean hasDescriptor (final String classVMName)
    {
        if ($assert.ENABLED) $assert.ASSERT (classVMName != null, "className is null");
        
        return m_classMap.containsKey (classVMName);
    }
    
    public Object lock ()
    {
        return m_classMap;
    }
        
    public boolean add (final ClassDescriptor cls, final boolean overwrite)
    {
        if ($assert.ENABLED) $assert.ASSERT (cls != null, "cls is null");
        
        final String classVMName = cls.getClassVMName ();
        
        if (overwrite || ! m_classMap.containsKey (classVMName))
        {
            m_classMap.put (classVMName, cls);
            
            boolean incompleteDebugInfo = false;

            if (! cls.hasSrcFileInfo ())
            {
                m_hasSrcFileInfo = false;
                incompleteDebugInfo = true;
            }
            
            if (! cls.hasCompleteLineNumberInfo ())
            {
                m_hasLineNumberInfo = false;
                incompleteDebugInfo = true;
            }
            
            // SF FR 971176: provide user with sample classes that may later
            // caused warnings about line coverage not available
            
            if (incompleteDebugInfo)
            {
                final Logger log = Logger.getLogger ();
                
                if (log.atINFO ())
                {
                    final String packageVMName = cls.getPackageVMName ();
                    
                    if (m_packagesWarned.add (packageVMName))
                    {
                        log.info ("package [" + packageVMName + "] contains classes [" + cls.getName () + "] without full debug info");
                    }
                }
            }
            
            return true;
        }

        return false;
    }
    
    // IMergeable:
    
    public boolean isEmpty ()
    {
        return m_classMap.isEmpty ();
    }
    
    /*
     * note: rhs entries must override current entries
     */
    public IMergeable merge (final IMergeable rhs)
    {
        if ((rhs == null) || rhs.isEmpty () || (rhs == this))
            return this;
        else
        {
            final MetaData rhsmdata = (MetaData) rhs; // TODO: redesign to avoid this cast?
            final Map rhsclasses = rhsmdata.m_classMap;
            
            // rhs entries always override existing content:
            
            for (Iterator entries = rhsclasses.entrySet ().iterator (); entries.hasNext (); )
            {
                final Map.Entry entry = (Map.Entry) entries.next ();
                
                final String classVMName = (String) entry.getKey ();
                final Object rhsdescriptor = entry.getValue ();
                    
                m_classMap.put (classVMName, rhsdescriptor);
            }
            
            // update debug info flags if necessary:
            
            if (! rhsmdata.hasSrcFileData ()) m_hasSrcFileInfo = false;
            if (! rhsmdata.hasLineNumberData ()) m_hasLineNumberInfo = false;
                
            return this;
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    
    MetaData (final CoverageOptions options)
    {
        if ($assert.ENABLED) $assert.ASSERT (options != null, "options is null");
        m_options = options;
        
        m_hasSrcFileInfo = true;
        m_hasLineNumberInfo = true;
        
        m_classMap = new HashMap ();
        m_packagesWarned = new HashSet ();
    }
    
    
    static MetaData readExternal (final DataInput in)
        throws IOException
    {
        final CoverageOptions options = CoverageOptions.readExternal (in);
        
        final boolean hasSrcFileInfo = in.readBoolean ();
        final boolean hasLineNumberInfo = in.readBoolean ();
        
        final int size = in.readInt ();
        final HashMap classMap = new HashMap (size);
        
        for (int i = 0; i < size; ++ i)
        {
            final String classVMName = in.readUTF ();
            final ClassDescriptor cls = ClassDescriptor.readExternal (in);
            
            classMap.put (classVMName, cls);
        }
        
        // [m_packagesWarned is not part of persisted state]
        
        return new MetaData (options, classMap, hasSrcFileInfo, hasLineNumberInfo);
    }
    
    static void writeExternal (final MetaData mdata, final DataOutput out)
        throws IOException
    {
        CoverageOptions.writeExternal (mdata.m_options, out);
        
        out.writeBoolean (mdata.m_hasSrcFileInfo);
        out.writeBoolean (mdata.m_hasLineNumberInfo);
        
        final Map classMap = mdata.m_classMap;
        
        final int size = classMap.size ();
        out.writeInt (size); // too bad the capacity is not visible
        
        final Iterator entries = classMap.entrySet ().iterator ();
        for (int i = 0; i < size; ++ i)
        {
            final Map.Entry entry = (Map.Entry) entries.next ();
            
            final String classVMName = (String) entry.getKey ();
            final ClassDescriptor cls = (ClassDescriptor) entry.getValue ();
            
            out.writeUTF (classVMName);
            ClassDescriptor.writeExternal (cls, out);
        }
        
        // [m_packagesWarned is not part of persisted state]
    }
    
    // private: ...............................................................
    
    
    private MetaData (final CoverageOptions options, final HashMap classMap,
                      final boolean hasSrcFileInfo, final boolean hasLineNumberInfo)
    {
        if ($assert.ENABLED) $assert.ASSERT (options != null, "options is null");
        m_options = options;
        
        m_hasSrcFileInfo = hasSrcFileInfo;
        m_hasLineNumberInfo = hasLineNumberInfo;
        
        m_classMap = classMap;
    }
    
    
    private final CoverageOptions m_options; // [never null]
    private boolean m_hasSrcFileInfo, m_hasLineNumberInfo;
    private /*final*/ HashMap /* classVMName:String->ClassDescriptor */ m_classMap; // [never null]
    
    private /*final*/ transient HashSet /*  packageVMName:String */ m_packagesWarned; // [never null]

} // end of class
// ----------------------------------------------------------------------------