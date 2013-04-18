/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: AbstractItemVisitor.java,v 1.1.1.1 2004/05/09 16:57:36 vlad_r Exp $
 */
package com.vladium.emma.report;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class AbstractItemVisitor implements IItemVisitor
{
    // public: ................................................................

    public Object visit (final AllItem item, final Object ctx)
    {
        return ctx;
    }

    public Object visit (final PackageItem item, final Object ctx)
    {
        return ctx;
    }

    public Object visit (final SrcFileItem item, final Object ctx)
    {
        return ctx;
    }

    public Object visit (final ClassItem item, final Object ctx)
    {
        return ctx;
    }

    public Object visit (final MethodItem item, final Object ctx)
    {
        return ctx;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------