/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: VerbosityCfg.java,v 1.1.2.1 2004/07/16 23:32:04 vlad_r Exp $
 */
package com.vladium.emma.ant;

import java.util.Properties;

import org.apache.tools.ant.types.EnumeratedAttribute;

import com.vladium.emma.AppLoggers;
import com.vladium.emma.EMMAProperties;
import com.vladium.logging.ILogLevels;
import com.vladium.util.IProperties;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2004
 */
public
final class VerbosityCfg
{
    // public: ................................................................
    

    public static final class VerbosityAttribute extends EnumeratedAttribute
    {
        public String [] getValues ()
        {
            return VALUES;
        }
        
        private static final String [] VALUES = new String []
            {
                ILogLevels.SEVERE_STRING,
                ILogLevels.SILENT_STRING,
                ILogLevels.WARNING_STRING,
                ILogLevels.QUIET_STRING,
                ILogLevels.INFO_STRING,
                ILogLevels.VERBOSE_STRING,
                ILogLevels.TRACE1_STRING,
                ILogLevels.TRACE2_STRING,
                ILogLevels.TRACE3_STRING,
            };

    } // end of nested class
    

    // verbosity attribute:
    
    public void setVerbosity (final VerbosityAttribute verbosity)
    {
        m_verbosity = verbosity.getValue ();
    }
    
    // verbosity class filter attribute:
    
    public void setVerbosityfilter (final String filter)
    {
        m_verbosityFilter = filter;
    }
    
    // ACCESSORS:
    
    public IProperties getSettings ()
    {
        IProperties settings = m_settings;
        if (settings == null)
        {
            settings = EMMAProperties.wrap (new Properties ());
            
            if ((m_verbosity != null) && (m_verbosity.trim ().length () > 0))
                settings.setProperty (AppLoggers.PROPERTY_VERBOSITY_LEVEL, m_verbosity.trim ());
            
            if ((m_verbosityFilter != null) && (m_verbosityFilter.trim ().length () > 0))
                settings.setProperty (AppLoggers.PROPERTY_VERBOSITY_FILTER, m_verbosityFilter.trim ());
            
            m_settings = settings;
            return settings;
        }
        
        return settings;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private String m_verbosity;
    private String m_verbosityFilter;
    
    private transient IProperties m_settings; // can be null

} // end of class
// ----------------------------------------------------------------------------