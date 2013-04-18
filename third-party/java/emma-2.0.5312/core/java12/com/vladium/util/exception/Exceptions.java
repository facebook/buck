/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Exceptions.java,v 1.1.1.1 2004/05/09 16:57:58 vlad_r Exp $
 */
package com.vladium.util.exception;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2002
 */
public
abstract class Exceptions
{
    // public: ................................................................
    
    public static boolean unexpectedFailure (final Throwable t, final Class [] expected)
    {
        if (t == null) return false;
        if (expected == null) return true;
        
        final Class reClass = t.getClass ();
        
        for (int e = 0; e < expected.length; ++ e)
        {
            if (expected [e] == null) continue;
            if (expected [e].isAssignableFrom (reClass))
                return false;
        }
        
        return true;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private Exceptions () {} // this class is not extendible

} // end of class
// ----------------------------------------------------------------------------