/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ReportDataModel.java,v 1.1.1.1 2004/05/09 16:57:38 vlad_r Exp $
 */
package com.vladium.emma.report;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.vladium.util.Descriptors;
import com.vladium.util.asserts.$assert;
import com.vladium.emma.IAppErrorCodes;
import com.vladium.emma.EMMARuntimeException;
import com.vladium.emma.data.ClassDescriptor;
import com.vladium.emma.data.IMetaData;
import com.vladium.emma.data.IMetadataConstants;
import com.vladium.emma.data.ICoverageData;
import com.vladium.emma.data.MethodDescriptor;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
final class ReportDataModel implements IReportDataModel
{
    // public: ................................................................


    public synchronized IReportDataView getView (final int viewType)
    {
        // TODO: merge the two branches together
        
        if (viewType >= m_views.length) throw new IllegalArgumentException ("invalid viewType: " + viewType);
        
        IReportDataView view = m_views [viewType];
        
        if (view != null)
            return view;
        else
        {
            final boolean srcView = viewType == IReportDataView.HIER_SRC_VIEW;
            
            if (srcView && ! m_mdata.hasSrcFileData ())
                throw new IllegalStateException ("source file data view requested for metadata with incomplete SourceFile debug info");

            final AllItem root = new AllItem ();
            final Map /* String(pkg name) -> PackageItem */ packageMap = new HashMap ();
            final Map /* String(pkg-prefixed src file name) -> ClassItem */ srcfileMap = new HashMap ();
            
            for (Iterator /* ClassDescriptor */ descriptors = m_mdata.iterator (); descriptors.hasNext (); )
            {    
                final ClassDescriptor cls = (ClassDescriptor) descriptors.next ();
                String packageVMName = cls.getPackageVMName ();
                
                PackageItem packageItem = (PackageItem) packageMap.get (packageVMName);
                if (packageItem == null)
                {
                    final String packageName = packageVMName.length () == 0 ? "default package" : Descriptors.vmNameToJavaName (packageVMName); 
                    packageItem = new PackageItem (root, packageName, packageVMName);
                    packageMap.put (packageVMName, packageItem);
                    
                    root.addChild (packageItem);
                }
                
                SrcFileItem srcfileItem = null;
                if (srcView)
                {                
                    final String srcFileName = cls.getSrcFileName ();
                    if ($assert.ENABLED) $assert.ASSERT (srcFileName != null, "src file name = null");
                    
                    final String fullSrcFileName = Descriptors.combineVMName (packageVMName, srcFileName);
                    
                    srcfileItem = (SrcFileItem) srcfileMap.get (fullSrcFileName);
                    if (srcfileItem == null)
                    {
                        srcfileItem = new SrcFileItem (packageItem, srcFileName, fullSrcFileName);
                        srcfileMap.put (fullSrcFileName, srcfileItem);
                        
                        packageItem.addChild (srcfileItem);
                    }
                }
                
                final ICoverageData.DataHolder data = m_cdata.getCoverage (cls);
                
                // check metadata and coverage data consistency:
                
                if (data != null)
                {
                    if (data.m_stamp != cls.getStamp ())
                        throw new EMMARuntimeException (IAppErrorCodes.CLASS_STAMP_MISMATCH,
                                                        new Object [] { Descriptors.vmNameToJavaName (cls.getClassVMName ()) }); 
                }
                
                final boolean [][] coverage = data != null ? data.m_coverage : null;
                
                if ($assert.ENABLED) $assert.ASSERT (! srcView || srcfileItem != null, "null srcfileItem");
                
                final ClassItem classItem = srcView ? new ClassItem (srcfileItem, cls, coverage) : new ClassItem (packageItem, cls, coverage);
                final MethodDescriptor [] methods = cls.getMethods ();
                
                // TODO: handle edge case when all methods of a class have METHOD_NO_BLOCK_DATA set
                for (int m = 0; m < methods.length; ++ m)
                {
                    final MethodDescriptor method = methods [m];
                        
                    if ((method.getStatus () & IMetadataConstants.METHOD_NO_BLOCK_DATA) != 0) continue;
                    
                    // TODO: wouldn't it be more consistent to simply pass the entire descriptor into MethodItems? (eval mem savings)
                    final MethodItem methodItem = new MethodItem (classItem, m, method.getName (), method.getDescriptor (), method.getFirstLine ());                    
                    // TODO: need to fold class's name into a method name prefix for collapsing case [only when it is not the same as the file name]
                    
                    classItem.addChild (methodItem);
                }
                
                if (srcView)
                    srcfileItem.addChild (classItem);
                else
                    packageItem.addChild (classItem);
            }
            
            view = new ReportDataView (root);
            
            m_views [viewType] = view;
            return view;
        }
    }

    // protected: .............................................................

    // package: ...............................................................

    
    ReportDataModel (final IMetaData mdata, final ICoverageData cdata)
    {
        if (mdata == null) throw new IllegalArgumentException ("null input: mdata");
        if (cdata == null) throw new IllegalArgumentException ("null input: cdata");
        
        m_views = new IReportDataView [2];
        
        // TODO: report generators work off data model views only; I should deref
        // mdata and cdata as soon as all possible views have been constructed and cached
        
        m_mdata = mdata;
        m_cdata = cdata;
    }
    
    // private: ...............................................................
    
    
    private static final class ReportDataView implements IReportDataView
    {
        public IItem getRoot()
        {
            return m_root;
        }
        
        ReportDataView (final IItem root)
        {
            m_root = root;
        }
        
        
        private final IItem m_root;
        
    } // end of nested class
    
    
    private final IMetaData m_mdata;
    private final ICoverageData m_cdata;
    
    private final IReportDataView [] m_views;

} // end of class
// ----------------------------------------------------------------------------