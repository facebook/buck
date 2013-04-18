/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: AttributeElementFactory.java,v 1.1.1.1 2004/05/09 16:57:47 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;
 
// ----------------------------------------------------------------------------
/**
 * @author (C) 2001, Vlad Roubtsov
 */
public
abstract class AttributeElementFactory
{
    // public: ................................................................
    
    public static IExceptionHandlerTable newExceptionHandlerTable (final int capacity)
    {
        return new ExceptionHandlerTable (capacity);
    }
    
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private AttributeElementFactory () {} // prevent subclassing

} // end of class
// ----------------------------------------------------------------------------
