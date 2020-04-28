package com.example.compilerplugin

import com.intellij.mock.MockProject
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.local.CoreLocalFileSystem
import com.intellij.openapi.vfs.local.CoreLocalVirtualFile
import com.intellij.psi.SingleRootFileViewProvider
import java.io.File
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension

object TestConfigurationKeys {
    val OUTPUT_DIR_KEY: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create<String>("output directory")
    val OLD_METHOD_NAME_KEY: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create<String>("old method name")
    val NEW_METHOD_NAME_KEY: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create<String>("new method name")
}

class TestCommandLineProcessor : CommandLineProcessor {
    companion object {
        const val TEST_PLUGIN_ID: String = "com.example.compilerplugin.test"
        val OUTPUT_DIR_OPTION: CliOption =
            CliOption("outputDir", "<directory>", "Directory to put transformed file")
        val OLD_METHOD_NAME_OPTION: CliOption =
            CliOption("oldMethodName", "<name>", "Method name to be replaced")
        val NEW_METHOD_NAME_OPTION: CliOption =
            CliOption("newMethodName", "<name>", "Method name to replace with")
    }

    override val pluginId: String = TEST_PLUGIN_ID
    override val pluginOptions: Collection<CliOption> =
        listOf(OUTPUT_DIR_OPTION, OLD_METHOD_NAME_OPTION, NEW_METHOD_NAME_OPTION)

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option) {
            OUTPUT_DIR_OPTION -> configuration.put(TestConfigurationKeys.OUTPUT_DIR_KEY, value)
            OLD_METHOD_NAME_OPTION -> configuration.put(TestConfigurationKeys.OLD_METHOD_NAME_KEY, value)
            NEW_METHOD_NAME_OPTION -> configuration.put(TestConfigurationKeys.NEW_METHOD_NAME_KEY, value)
            else -> throw CliOptionProcessingException("Unknown option: ${option.optionName}")
        }
    }
}

class TestComponentRegistrar : ComponentRegistrar {
    override fun registerProjectComponents(project: MockProject, configuration: CompilerConfiguration) {
        val messageCollector = configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        messageCollector.report(CompilerMessageSeverity.INFO, "Project component registration")

        val extension = TestPluginExtension(
            configuration.get(TestConfigurationKeys.OUTPUT_DIR_KEY)!!,
            configuration.get(TestConfigurationKeys.OLD_METHOD_NAME_KEY)!!,
            configuration.get(TestConfigurationKeys.NEW_METHOD_NAME_KEY)!!)
        AnalysisHandlerExtension.registerExtension(project, extension)
    }
}

class TestPluginExtension(
    private val outputDir: String,
    private val oldMethodName: String,
    private val newMethodName: String) : AnalysisHandlerExtension {

    override fun doAnalysis(
        project: Project,
        module: ModuleDescriptor,
        projectContext: ProjectContext,
        files: Collection<KtFile>,
        bindingTrace: BindingTrace,
        componentProvider: ComponentProvider
    ): AnalysisResult? {
        files as ArrayList<KtFile>
        for (i in files.indices) {
            val oldFile = files[i]
            val newFileText = oldFile.text.replace("$oldMethodName()", "$newMethodName()")
            files[i] = createNewKtFile(oldFile, newFileText)
        }
        return null
    }

    private fun createNewKtFile(oldFile: KtFile, newFileText: String): KtFile {
        val directory = File(outputDir)
        directory.mkdirs()
        val virtualFile =
            CoreLocalVirtualFile(
                CoreLocalFileSystem(), File(directory, oldFile.name).apply { writeText(newFileText) })
        return KtFile(SingleRootFileViewProvider(oldFile.manager, virtualFile), false)
    }
}
