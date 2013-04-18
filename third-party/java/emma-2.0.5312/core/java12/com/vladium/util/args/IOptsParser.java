/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IOptsParser.java,v 1.1.1.1 2004/05/09 16:57:56 vlad_r Exp $
 */
package com.vladium.util.args;

import java.io.PrintWriter;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2002
 */
public
interface IOptsParser
{
    // public: ................................................................

    int SHORT_USAGE     = 1;
    int DETAILED_USAGE  = 2;

    interface IOpt
    {
        String getName ();
        String getCanonicalName ();
        
        String getPatternPrefix ();
        
        int getValueCount ();
        String getFirstValue ();
        String [] getValues ();
      
    } // end of interface
    
    
    interface IOpts
    {
        /**
         * 0: none, 1: short, 2: detailed
         * 
         * @return
         */
        int usageRequestLevel ();
        void error (PrintWriter out, int width);
        
        IOpt [] getOpts ();
        boolean hasArg (String name);
        
        IOpt [] getOpts (String pattern);
        
        /**
         * 
         * @return [never null, could be empty]
         */
        String [] getFreeArgs ();
        
    } // end of interface
    
    void usage (PrintWriter out, int level, int width);
    IOpts parse (String [] args);
    
    abstract class Factory
    {
        // TODO: pass short/long usage opt names in?
        
        public static IOptsParser create (final String metadataResourceName, final ClassLoader loader,
                                          final String msgPrefix, final String [] usageOpts)
        {
            return new OptsParser (metadataResourceName, loader, msgPrefix, usageOpts);
        }
        
    } // end of nested class
    
} // end of interface
// ----------------------------------------------------------------------------