/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IClassDefVisitor.java,v 1.1.1.1 2004/05/09 16:57:46 vlad_r Exp $
 */
package com.vladium.jcd.cls;

// ----------------------------------------------------------------------------
/**
 * @author (C) 2001, Vlad Roubtsov
 */
public interface IClassDefVisitor
{
    // public: ................................................................
    
    Object visit (ClassDef cls, Object ctx);
    
    Object visit (IConstantCollection constants, Object ctx);
    Object visit (IInterfaceCollection interfaces, Object ctx);
    Object visit (IFieldCollection fields, Object ctx);
    Object visit (IMethodCollection methods, Object ctx);
    Object visit (IAttributeCollection attributes, Object ctx);

} // end of interface
// ----------------------------------------------------------------------------
