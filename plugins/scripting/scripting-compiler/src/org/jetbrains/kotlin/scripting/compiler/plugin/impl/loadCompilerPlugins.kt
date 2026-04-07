/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("DEPRECATION_ERROR")

package org.jetbrains.kotlin.scripting.compiler.plugin.impl

import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.checkPluginsArguments
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.plugins.PluginCliParser
import org.jetbrains.kotlin.cli.plugins.processCompilerPluginsOptions
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCommandLineProcessor
import org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCompilerConfigurationComponentRegistrar
import org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingK2CompilerPluginRegistrar
import kotlin.script.experimental.jvm.util.forAllMatchingFiles

private const val SCRIPT_COMPILATION_DISABLE_PLUGINS_PROPERTY = "script.compilation.disable.plugins"
private const val SCRIPT_COMPILATION_DISABLE_COMMANDLINE_PROCESSORS_PROPERTY = "script.compilation.disable.commandline.processors"

private val scriptCompilationDisabledPlugins =
    listOf(
        ScriptingCompilerConfigurationComponentRegistrar::class.java.name,
        ScriptingK2CompilerPluginRegistrar::class.java.name
    )

private val scriptCompilationDisabledCommandlineProcessors =
    listOf(
        ScriptingCommandLineProcessor::class.java.name
    )

internal fun CompilerConfiguration.loadPluginsFromClassloader(classLoader: ClassLoader) {
    val registrars =
        classLoader.loadServices<ComponentRegistrar>(scriptCompilationDisabledPlugins, SCRIPT_COMPILATION_DISABLE_PLUGINS_PROPERTY)
    addAll(ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS, registrars)
    val k2Registrars =
        classLoader.loadServices<CompilerPluginRegistrar>(scriptCompilationDisabledPlugins, SCRIPT_COMPILATION_DISABLE_PLUGINS_PROPERTY)
    addAll(CompilerPluginRegistrar.COMPILER_PLUGIN_REGISTRARS, k2Registrars)

}

/**
 * Loads compiler plugins from -Xplugin and -Xcompiler-plugin arguments, registers their extensions
 * into the configuration's extension storage, and returns true if any new plugins were loaded.
 * This is used during script refinement to support @file:CompilerOptions("-Xplugin=...").
 */
internal fun CompilerConfiguration.loadPluginsFromArguments(
    arguments: K2JVMCompilerArguments,
    parentDisposable: Disposable
): Boolean {
    val pluginClasspaths = arguments.pluginClasspaths.asList()
    val pluginOptions = arguments.pluginOptions.asList()
    val pluginConfigurations = arguments.pluginConfigurations.asList()
    val pluginOrderConstraints = arguments.pluginOrderConstraints.asList()

    if (pluginClasspaths.isEmpty() && pluginConfigurations.isEmpty()) return false

    val existingRegistrarCount = getList(CompilerPluginRegistrar.COMPILER_PLUGIN_REGISTRARS).size

    checkPluginsArguments(this, false, pluginClasspaths, pluginOptions, pluginConfigurations)
    PluginCliParser.loadPluginsSafe(
        pluginClasspaths, pluginOptions, pluginConfigurations,
        pluginOrderConstraints, this, parentDisposable
    )

    // Register extensions from newly loaded plugins into the configuration's extension storage
    val allRegistrars = getList(CompilerPluginRegistrar.COMPILER_PLUGIN_REGISTRARS)
    val newRegistrars = allRegistrars.drop(existingRegistrarCount)
    if (newRegistrars.isEmpty()) return false

    val extensionStorage = extensionsStorage
    if (extensionStorage != null) {
        for (registrar in newRegistrars) {
            with(registrar) { extensionStorage.registerExtensions(this@loadPluginsFromArguments) }
        }
    }
    return true
}

internal fun CompilerConfiguration.processPluginsCommandLine(arguments: K2JVMCompilerArguments) {
    val classLoader = CompilerConfiguration::class.java.classLoader
    val pluginOptions = arguments.pluginOptions.asIterable()

    val commandLineProcessors =
        classLoader.loadServices<CommandLineProcessor>(
            scriptCompilationDisabledCommandlineProcessors, SCRIPT_COMPILATION_DISABLE_COMMANDLINE_PROCESSORS_PROPERTY
        )
    processCompilerPluginsOptions(this, pluginOptions, commandLineProcessors)
}

private inline fun <reified Service : Any> ClassLoader.loadServices(disabled: List<String>, disablingProperty: String): List<Service> {
    val disabledServiceNames = disabled.toHashSet()
    System.getProperty(disablingProperty)?.let {
        it.split(',', ';', ' ').forEach { name ->
            disabledServiceNames.add(name.trim())
        }
    }
    return loadServices {
        !disabledServiceNames.contains(it) && !disabledServiceNames.contains(it.substringAfterLast('.'))
    }
}

private const val SERVICE_DIRECTORY_LOCATION = "META-INF/services/"

private inline fun <reified Service : Any> ClassLoader.loadServices(isEnabled: (String) -> Boolean): List<Service> {
    val registrarsNames = HashSet<String>()
    val serviceFileName = SERVICE_DIRECTORY_LOCATION + Service::class.java.name

    forAllMatchingFiles(serviceFileName, serviceFileName) { name, stream ->
        stream.reader().useLines {
            it.mapNotNullTo(registrarsNames) { parseServiceFileLine(name, it) }
        }
    }

    return registrarsNames.mapNotNull { if (isEnabled(it)) (loadClass(it).newInstance() as Service) else null }
}

private fun parseServiceFileLine(location: String, line: String): String? {
    val actualLine = line.substringBefore('#').trim().takeIf { it.isNotEmpty() } ?: return null
    actualLine.forEachIndexed { index: Int, c: Char ->
        val isValid = if (index == 0) Character.isJavaIdentifierStart(c) else Character.isJavaIdentifierPart(c) || c == '.'
        if (!isValid) {
            val errorText = "Invalid Java identifier: $line"
            throw RuntimeException("Error loading services from $location : $errorText")
        }
    }
    return actualLine
}
