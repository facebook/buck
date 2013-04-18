/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: FilterCfg.java,v 1.2 2004/05/20 02:28:07 vlad_r Exp $
 */
package com.vladium.emma.instr;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

import com.vladium.util.IConstants;
import com.vladium.util.Strings;
import com.vladium.emma.ant.StringValue;
import com.vladium.emma.ant.SuppressableTask;
import com.vladium.emma.filter.IInclExclFilter;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
class FilterCfg
{
    // public: ................................................................
    
    
    public static final class filterElement extends StringValue
    {
        public filterElement (final Task task)
        {
            super (task);
        }
        
        public void setValue (final String value)
        {
            final String [] specs = Strings.merge (new String [] {value}, COMMA_DELIMITERS, true);
            
            for (int i = 0; i < specs.length; ++ i)
            {
                final String spec = specs [i];
                
                if (spec.startsWith (IInclExclFilter.INCLUSION_PREFIX_STRING) ||
                    spec.startsWith (IInclExclFilter.EXCLUSION_PREFIX_STRING))
                {
                    appendValue (spec, COMMA);
                }
                else
                {
                    appendValue (IInclExclFilter.INCLUSION_PREFIX + spec, COMMA); // default to inclusion
                }
            }
        }
        
        /**
         * Set the 'file' attribute.
         */
        public void setFile (final File file)
        {
            appendValue ("@".concat (file.getAbsolutePath ()), COMMA); // actual file I/O delayed until getFilterSpecs()
        }
        
        public void setIncludes (final String value)
        {
            final String [] specs = Strings.merge (new String [] {value}, COMMA_DELIMITERS, true);
            
            for (int i = 0; i < specs.length; ++ i)
            {
                final String spec = specs [i];
                
                if (spec.startsWith (IInclExclFilter.INCLUSION_PREFIX_STRING))
                {
                    appendValue (spec, COMMA);
                }
                else
                {
                    if (spec.startsWith (IInclExclFilter.EXCLUSION_PREFIX_STRING))
                        appendValue (IInclExclFilter.INCLUSION_PREFIX + spec.substring (1), COMMA); // override
                    else
                        appendValue (IInclExclFilter.INCLUSION_PREFIX + spec, COMMA);
                }
            }
        }
        
        public void setExcludes (final String value)
        {
            final String [] specs = Strings.merge (new String [] {value}, COMMA_DELIMITERS, true);
            
            for (int i = 0; i < specs.length; ++ i)
            {
                final String spec = specs [i];
                
                if (spec.startsWith (IInclExclFilter.EXCLUSION_PREFIX_STRING))
                {
                    appendValue (spec, COMMA);
                }
                else
                {
                    if (spec.startsWith (IInclExclFilter.INCLUSION_PREFIX_STRING))
                        appendValue (IInclExclFilter.EXCLUSION_PREFIX + spec.substring (1), COMMA); // override
                    else
                        appendValue (IInclExclFilter.EXCLUSION_PREFIX + spec, COMMA);
                }
            }
        }
        
    } // end of nested class
    

    public FilterCfg (final Task task)
    {
        if (task == null) throw new IllegalArgumentException ("null input: task");
        
        m_task = task;
        m_filterList = new ArrayList ();
    }

    
    // filter attribute/element:
    
    public void setFilter (final String filter)
    {
        createFilter ().appendValue (filter, COMMA);
    }
    
    public filterElement createFilter ()
    {
        final filterElement result = new filterElement (m_task);
        m_filterList.add (result);
        
        return result;
    }
    
    // ACCESSORS:

    public String [] getFilterSpecs ()
    {
        if (m_specs != null)
            return m_specs;
        else
        {           
            if ((m_filterList == null) || m_filterList.isEmpty ())
            {
                m_specs = IConstants.EMPTY_STRING_ARRAY;
            }
            else
            {
                final String [] values = new String [m_filterList.size ()];
                
                int j = 0;
                for (Iterator i = m_filterList.iterator (); i.hasNext (); ++ j)
                    values [j] = ((StringValue) i.next ()).getValue ();
                                
                try
                {
                    m_specs = Strings.mergeAT (values, COMMA_DELIMITERS, true);
                }
                catch (IOException ioe)
                {
                    throw (BuildException) SuppressableTask.newBuildException (m_task.getTaskName ()
                        + ": I/O exception while processing input" , ioe, m_task.getLocation ()).fillInStackTrace ();
                }
            }
            
            return m_specs;
        }
    }
    
    // protected: .............................................................


    protected static final String COMMA               = ",";
    protected static final String COMMA_DELIMITERS    = COMMA + Strings.WHITE_SPACE;
    protected static final String PATH_DELIMITERS     = COMMA.concat (File.pathSeparator);
    
    // package: ...............................................................
    
    // private: ...............................................................

    
    private final Task m_task; // never null
    private final List /* filterElement */ m_filterList; // never null
    
    private transient String [] m_specs;

} // end of class
// ----------------------------------------------------------------------------