/* Copyright (C) 2004 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Processor.java,v 1.1.2.1 2004/07/16 23:32:03 vlad_r Exp $
 */
package com.vladium.emma;

import java.util.Properties;

import com.vladium.logging.Logger;
import com.vladium.util.IProperties;
import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2004
 */
public
abstract class Processor
{
    // public: ................................................................


    public synchronized void run ()
    {
        validateState ();
        
        // load tool properties:
        final IProperties toolProperties;
        {
            final IProperties appProperties = EMMAProperties.getAppProperties ();
            
            toolProperties = IProperties.Factory.combine (m_propertyOverrides, appProperties);
        }
        if ($assert.ENABLED) $assert.ASSERT (toolProperties != null, "toolProperties is null"); // can be empty, though

        final Logger current = Logger.getLogger ();
        final Logger log = AppLoggers.create (m_appName, toolProperties, current);
        
        if (log.atTRACE1 ())
        {
            log.trace1 ("run", "complete tool properties:");
            toolProperties.list (log.getWriter ());
        }
        
        try
        {
            Logger.push (log);
            m_log = log;
        
            _run (toolProperties);
        }
        finally
        {
            if (m_log != null)
            {
                Logger.pop (m_log);
                m_log = null;
            }
        }
    }

    
    public synchronized final void setAppName (final String appName)
    {
        m_appName = appName;
    }
    
    /**
     * 
     * @param overrides [may be null (unsets the previous overrides)]
     */
    public synchronized final void setPropertyOverrides (final Properties overrides)
    {
        m_propertyOverrides = EMMAProperties.wrap (overrides);
    }
    
    /**
     * 
     * @param overrides [may be null (unsets the previous overrides)]
     */
    public synchronized final void setPropertyOverrides (final IProperties overrides)
    {
        m_propertyOverrides = overrides;
    }
    
    // protected: .............................................................
    
    
    protected Processor ()
    {
        // not publicly instantiable
    }

    protected abstract void _run (IProperties toolProperties);

    
    protected void validateState ()
    {
        // no Processor state needs validation
        
        // [m_appName allowed to be null]
        // [m_propertyOverrides allowed to be null]
    }
    
    
    protected String m_appName; // used as logging prefix, can be null
    protected IProperties m_propertyOverrides; // user override; can be null/empty for run()
    protected Logger m_log; // not null only within run()

    // package: ...............................................................
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------