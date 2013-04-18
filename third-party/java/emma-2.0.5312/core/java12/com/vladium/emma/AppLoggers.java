/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: AppLoggers.java,v 1.1.2.1 2004/07/16 23:32:03 vlad_r Exp $
 */
package com.vladium.emma;

import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

import com.vladium.logging.ILogLevels;
import com.vladium.logging.Logger;
import com.vladium.util.IProperties;
import com.vladium.util.Strings;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2004
 */
public
abstract class AppLoggers
{
    // public: ................................................................
    
    public static final String PREFIX_VERBOSITY                 = "verbosity.";

    public static final String PROPERTY_VERBOSITY_LEVEL         = PREFIX_VERBOSITY + "level";
    public static final String DEFAULT_VERBOSITY_LEVEL          = ILogLevels.INFO_STRING;
    
    public static final String PROPERTY_VERBOSITY_FILTER        = PREFIX_VERBOSITY + "filter";
    
    public static Logger create (final String appName, final IProperties properties, final Logger base)
    {
        if (properties == null)
            throw new IllegalArgumentException ("null input: properties");
        
        // verbosity level:
        
        final int level;
        {
            final String _level = properties.getProperty (PROPERTY_VERBOSITY_LEVEL,
                                                          DEFAULT_VERBOSITY_LEVEL);
            level = Logger.stringToLevel (_level);
        }
        
        // verbosity filter:
        
        final Set filter;
        {
            final String _filter = properties.getProperty (PROPERTY_VERBOSITY_FILTER);
            Set temp = null;
            
            if (_filter != null)
            {
                final StringTokenizer tokenizer = new StringTokenizer (_filter, COMMA_DELIMITERS);
                if (tokenizer.countTokens () > 0)
                {
                    temp = new HashSet (tokenizer.countTokens ());
                    while (tokenizer.hasMoreTokens ())
                    {
                        temp.add (tokenizer.nextToken ());
                    }
                }
            }
            
            filter = temp;
        }
        
        return Logger.create (level, null, appName, filter, base);
    }
    
    
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    

    private AppLoggers () {} // this class is not extendible
    
    private static final String COMMA_DELIMITERS    = "," + Strings.WHITE_SPACE;

} // end of class
// ----------------------------------------------------------------------------