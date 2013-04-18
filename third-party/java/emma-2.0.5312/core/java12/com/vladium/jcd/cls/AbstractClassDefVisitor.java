/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: AbstractClassDefVisitor.java,v 1.1.1.1 2004/05/09 16:57:44 vlad_r Exp $
 */
package com.vladium.jcd.cls;

// ----------------------------------------------------------------------------
/**
 * @author (C) 2001, Vlad Roubtsov
 */
public
abstract class AbstractClassDefVisitor implements IClassDefVisitor
{
    // public: ................................................................

    // IClassDefVisitor:

    public Object visit (final ClassDef cls, final Object ctx)
    {
        visit (cls.getConstants (), ctx);
        visit (cls.getInterfaces (), ctx);
        visit (cls.getFields (), ctx);
        visit (cls.getMethods (), ctx);
        visit (cls.getAttributes (), ctx);
        
        return ctx;
    }

    public Object visit (final IAttributeCollection attributes, final Object ctx)
    {
        return ctx;
    }

    public Object visit (final IConstantCollection constants, final Object ctx)
    {
        return ctx;
    }

    public Object visit (final IFieldCollection fields, final Object ctx)
    {
        return ctx;
    }

    public Object visit (final IInterfaceCollection interfaces, final Object ctx)
    {
        return ctx;
    }

    public Object visit (final IMethodCollection methods, final Object ctx)
    {
        return ctx;
    }
        
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
