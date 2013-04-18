/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: InstrClassLoadHook.java,v 1.1.1.1 2004/05/09 16:57:44 vlad_r Exp $
 */
package com.vladium.emma.rt;

import java.io.IOException;

import com.vladium.jcd.cls.ClassDef;
import com.vladium.jcd.compiler.ClassWriter;
import com.vladium.jcd.parser.ClassDefParser;
import com.vladium.util.ByteArrayOStream;
import com.vladium.util.Descriptors;
import com.vladium.util.asserts.$assert;
import com.vladium.emma.filter.IInclExclFilter;
import com.vladium.emma.instr.InstrVisitor;
import com.vladium.emma.data.CoverageOptions;
import com.vladium.emma.data.IMetaData;

// ----------------------------------------------------------------------------
/**
 * MT-safety ensured by the containing loader
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class InstrClassLoadHook implements IClassLoadHook
{
    // public: ................................................................
    
    /**
     * @param filter [can be null]
     */
    public InstrClassLoadHook (final IInclExclFilter filter, final IMetaData mdata)
    {
        if (mdata == null) throw new IllegalArgumentException ("null input: mdata");
        
        m_filter = filter; // can be null 
        m_metadata = mdata;
        
        // important to use the same options as the metadata may have been populated earlier:
        final CoverageOptions options = mdata.getOptions ();
        m_classDefProcessor = new InstrVisitor (options);
        
        m_instrResult = new InstrVisitor.InstrResult ();
    }
    
        
    public boolean processClassDef (final String className,
                                    final byte [] bytes, final int length,
                                    ByteArrayOStream out)
        throws IOException
    {
        if ($assert.ENABLED)
        {
            $assert.ASSERT (className != null, "className is null");
            $assert.ASSERT (bytes != null, "bytes is null");
            $assert.ASSERT (bytes != null, "out is null");
        }
        
        final IInclExclFilter filter = m_filter;
        if ((filter == null) || filter.included (className))
        {
            final ClassDef clsDef = ClassDefParser.parseClass (bytes, length);
            final String classVMName = Descriptors.javaNameToVMName (className);
            
            final Object lock = m_metadata.lock ();
            
            final boolean metadataExists;
            synchronized (lock)
            {
                metadataExists = m_metadata.hasDescriptor (classVMName);
            }
            
            // since this is the first [and only] time the parent loader is
            // loading the class in question, if metadata for 'className' exists
            // it means it was created during the app runner's classpath scan --
            // do not overwrite it (the class def should be the same)
            
            // [this picture breaks down if the design changes so that the same
            // metadata instance could be associated with more than one app loader]
            
            m_classDefProcessor.process (clsDef, false, true, ! metadataExists, m_instrResult);
            
            boolean useOurs = m_instrResult.m_instrumented;
            
            if (m_instrResult.m_descriptor != null) // null means either the metadata existed already or the class is an interface
            {
                // try to update metadata [this supports the "no initial full cp
                // scan mode" in the app runner and also ensures that we pick up
                // any dynamically generated classes to support (hacky) apps that
                // do dynamic source generation/compilation]:
                
                synchronized (lock)
                {
                    // do not force overwrites of existing descriptors to support
                    // correct handling of race conditions: if another thread
                    // updates the metadata first, discard our version of the class def
                    
                    // [actually, this guard is redundant here because
                    // right now the hook can only have a single classloader parent
                    // and the parent's loadClass() is a critical section]
                    
                    if (! m_metadata.add (m_instrResult.m_descriptor, false))
                         useOurs = false; 
                }
            }
            
            if (useOurs)
            {                        
                ClassWriter.writeClassTable (clsDef, out);
                return true;
            }
        }

        return false;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private final IInclExclFilter m_filter; // can be null [equivalent to no filtering]
    private final IMetaData m_metadata; // never null
    private final InstrVisitor m_classDefProcessor; // never null
    private final InstrVisitor.InstrResult m_instrResult;

} // end of class
// ----------------------------------------------------------------------------