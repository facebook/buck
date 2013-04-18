/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IANTVersion.java,v 1.1.1.1.2.1 2004/07/10 03:34:52 vlad_r Exp $
 */
package com.vladium.emma.ant;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.apache.tools.ant.types.FileSet;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2004
 */
public
interface IANTVersion
{
    // public: ................................................................
    
    /** 'true' iff the current runtime version is 1.2 or later */
    boolean ANT_1_5_PLUS = _ANTVersion._ANT_1_5_PLUS; // static final but not inlinable
    
    
    abstract class _ANTVersion
    {
        static final boolean _ANT_1_5_PLUS; // set in <clinit>
        
        private _ANTVersion () {  /* prevent subclassing */ }
    
        static
        {
            boolean temp = true;
            try
            {
                final Method m = FileSet.class.getMethod ("setFile", new Class [] { File.class });
                
                // [assertion: 'm' is public]
                
                final int modifiers = m.getModifiers ();
                if ((modifiers & Modifier.STATIC) != 0)
                    temp = false;
            }
            catch (NoSuchMethodException nsme)
            {
                temp = false;
            }
            catch (SecurityException se)
            {
                temp = false;
            }
            catch (Throwable t)
            {
                t.printStackTrace (System.out);
                temp = false;
            }
            
            _ANT_1_5_PLUS = temp;
        }

    } // end of nested class

} // end of interface
// ----------------------------------------------------------------------------