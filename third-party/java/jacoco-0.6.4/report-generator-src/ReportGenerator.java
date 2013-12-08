/*******************************************************************************
 * Source of this file 'http://www.eclemma.org/jacoco/trunk/doc/examples/java/ReportGenerator.java'
 * We have modified it to make it compatible with Buck's usage.
 *
 * Copyright (c) 2009, 2013 Mountainminds GmbH & Co. KG and Contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Brock Janiczak - initial API and implementation
 *
 *******************************************************************************/
import java.io.File;
import java.io.IOException;
import java.lang.Package;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.html.HTMLFormatter;

/**
 * This example creates a HTML report for eclipse like projects based on a
 * single execution data store called jacoco.exec. The report contains no
 * grouping information.
 * 
 * The class files under test must be compiled with debug information, otherwise
 * source highlighting will not work.
 */
public class ReportGenerator {

	private final String title;

	private final File executionDataFile;
	private final String classesPath;
	private final File sourceDirectory;
	private final File reportDirectory;

	private ExecFileLoader execFileLoader;

	/**
	 * Create a new generator based for the given project.
	 * 
	 * @param projectDirectory
	 */
	public ReportGenerator(final File projectDirectory) {
		String jacoco_output_dir = System.getProperty("jacoco.output.dir");
		this.title = "Code-Coverage Analysis";
		System.out.println(jacoco_output_dir);
		this.executionDataFile = new File(jacoco_output_dir,System.getProperty("jacoco.exec.data.file"));
		this.classesPath = System.getProperty("classes.dir");
		this.sourceDirectory = new File(System.getProperty("src.dir"));
		this.reportDirectory = new File(jacoco_output_dir,"code-coverage");
	}

	/**
	 * Create the report.
	 * 
	 * @throws IOException
	 */
	public void create() throws IOException {

		// Read the jacoco.exec file. Multiple data files could be merged
		// at this point
		loadExecutionData();

		// Run the structure analyzer on a single class folder to build up
		// the coverage model. The process would be similar if your classes
		// were in a jar file. Typically you would create a bundle for each
		// class folder and each jar you want in your report. If you have
		// more than one bundle you will need to add a grouping node to your
		// report
		final IBundleCoverage bundleCoverage = analyzeStructure();

		createReport(bundleCoverage);

	}

	private void createReport(final IBundleCoverage bundleCoverage)
			throws IOException {

		// Create a concrete report visitor based on some supplied
		// configuration. In this case we use the defaults
		final HTMLFormatter htmlFormatter = new HTMLFormatter();
		final IReportVisitor visitor = htmlFormatter
				.createVisitor(new FileMultiReportOutput(reportDirectory));

		// Initialize the report with all of the execution and session
		// information. At this point the report doesn't know about the
		// structure of the report being created
		visitor.visitInfo(execFileLoader.getSessionInfoStore().getInfos(),
				execFileLoader.getExecutionDataStore().getContents());

		// Populate the report structure with the bundle coverage information.
		// Call visitGroup if you need groups in your report.
		visitor.visitBundle(bundleCoverage, new DirectorySourceFileLocator(
				sourceDirectory, "utf-8", 4));

		// Signal end of structure information to allow report to write all
		// information out
		visitor.visitEnd();

	}

	private void loadExecutionData() throws IOException {
		execFileLoader = new ExecFileLoader();
    executionDataFile.createNewFile();
		execFileLoader.load(executionDataFile);
	}

	private IBundleCoverage analyzeStructure() throws IOException {
		final CoverageBuilder coverageBuilder = new CoverageBuilder();
		final Analyzer analyzer = new Analyzer(
				execFileLoader.getExecutionDataStore(), coverageBuilder);

    String[] jarsPath = classesPath.split(":");
    for(String jarPath : jarsPath) {
      analyzer.analyzeAll(new File(jarPath));
    }

		return coverageBuilder.getBundle(title);
	}

	/**
	 * Starts the report generation process
	 * 
	 * @param args
	 *            Arguments to the application. This will be the location of the
	 *            eclipse projects that will be used to generate reports for
	 * @throws IOException
	 */
	public static void main(final String[] args) throws IOException {
			final ReportGenerator generator = new ReportGenerator(null);
			generator.create();
	}

}
