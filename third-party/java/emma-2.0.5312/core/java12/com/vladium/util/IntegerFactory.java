/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IntegerFactory.java,v 1.1.1.1 2004/05/09 16:57:52 vlad_r Exp $
 */
package com.vladium.util;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class IntegerFactory
{
    // public: ................................................................
    
    // TODO: use thread-local arena pattern to avoid synchronization ?
    
    public static Integer getInteger (final int value)
    {
        synchronized (s_values)
        {
            final Object _result = s_values.get (value);
            
            if (_result == null)
            {
                final Integer result = new Integer (value);
                s_values.put (value, result);
                
                return result; 
            }
            
            return (Integer) _result;
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private IntegerFactory () {} // prevent subclassing
    
    
    private static final IntObjectMap s_values = new IntObjectMap (16661);

} // end of class
// ----------------------------------------------------------------------------