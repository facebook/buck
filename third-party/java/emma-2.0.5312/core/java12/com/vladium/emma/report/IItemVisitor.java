/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IItemVisitor.java,v 1.1.1.1 2004/05/09 16:57:37 vlad_r Exp $
 */
package com.vladium.emma.report;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public interface IItemVisitor
{
    // public: ................................................................
    
    Object visit (AllItem item, Object ctx);
    Object visit (PackageItem item, Object ctx);
    Object visit (SrcFileItem item, Object ctx);
    Object visit (ClassItem item, Object ctx);
    Object visit (MethodItem item, Object ctx);

} // end of interface
// ----------------------------------------------------------------------------