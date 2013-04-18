/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: SuppressableTask.java,v 1.1.1.1.2.2 2004/07/16 23:32:04 vlad_r Exp $
 */
package com.vladium.emma.ant;

import java.io.File;

import com.vladium.emma.IAppConstants;
import com.vladium.util.IProperties;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Location;
import org.apache.tools.ant.Task;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class SuppressableTask extends Task
{
    // public: ................................................................
    
    
    public void init () throws BuildException
    {
        super.init ();
        
        m_verbosityCfg = new VerbosityCfg ();
        m_genericCfg = new GenericCfg (this);
    }

    /**
     * Set the optional 'enabled' attribute [defaults to 'true'].
     */
    public final void setEnabled (final boolean enabled)
    {
        m_enabled = enabled;
    }
    
    public final boolean isEnabled ()
    {
        return m_enabled;
    }
    
    // verbosity attribute:
    
    public void setVerbosity (final VerbosityCfg.VerbosityAttribute verbosity)
    {
        m_verbosityCfg.setVerbosity (verbosity);
    }
    
    // verbosity class filter attribute:
    
    public void setVerbosityfilter (final String filter)
    {
        m_verbosityCfg.setVerbosityfilter (filter);
    }

    // .properties file attribute:
    
    public final void setProperties (final File file)
    {
        m_genericCfg.setProperties (file);
    }

    // generic property element:
    
    public final PropertyElement createProperty ()
    {
        return m_genericCfg.createProperty ();
    }
    
    
    public static BuildException newBuildException (final String msg, final Location location)
    {
        final String prefixedMsg = ((msg == null) || (msg.length () == 0))
            ? msg
            : IAppConstants.APP_THROWABLE_BUILD_ID + " " + msg;
       
        return new BuildException (prefixedMsg, location); 
    }
    
    public static BuildException newBuildException (final String msg, final Throwable cause, final Location location)
    {
        final String prefixedMsg = ((msg == null) || (msg.length () == 0))
            ? msg
            : IAppConstants.APP_THROWABLE_BUILD_ID + " " + msg;
       
        return new BuildException (prefixedMsg, cause, location); 
    }
    
    // protected: .............................................................
    
    
    protected SuppressableTask ()
    {
        m_enabled = true; // by default, all tasks are enabled
    }
    
    protected IProperties getTaskSettings ()
    {
        // (1) by default, generic settings are always more specific than any file settings
        
        // (2) verbosity settings use dedicated attributes and hence are more specific
        // than anything generic
        
        final IProperties fileSettings = m_genericCfg.getFileSettings ();
        final IProperties genericSettings = m_genericCfg.getGenericSettings (); 
        final IProperties verbositySettings = m_verbosityCfg.getSettings ();
        
        return IProperties.Factory.combine (verbositySettings,
               IProperties.Factory.combine (genericSettings,
                                            fileSettings));
    }    
    
    // package: ...............................................................

    // private: ...............................................................
    
    
    private /*final*/ VerbosityCfg m_verbosityCfg;
    private /*final*/ GenericCfg m_genericCfg;
    private boolean m_enabled;
    
} // end of class
// ----------------------------------------------------------------------------