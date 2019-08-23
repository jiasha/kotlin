/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import org.gradle.api.Project
import org.jetbrains.plugins.gradle.tooling.ErrorMessageBuilder
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService
import java.io.*
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

interface GradleKotlinBuildScriptsModel : Serializable {
    val scripts: List<GradleKotlinBuildScriptModel>
}

data class GradleKotlinBuildScriptsModelImpl(
    override val scripts: List<GradleKotlinBuildScriptModel>
) : GradleKotlinBuildScriptsModel

interface GradleKotlinBuildScriptModel : Serializable {
    val file: String
    val classPath: List<String>
    val sourcePath: List<String>
    val imports: List<String>
}

data class GradleKotlinBuildScriptModelImpl(
    override val file: String,
    override val classPath: List<String>,
    override val sourcePath: List<String>,
    override val imports: List<String>
) : GradleKotlinBuildScriptModel

class KotlinGradleBuildScriptsModelBuilder : ModelBuilderService {
    override fun getErrorMessageBuilder(project: Project, e: Exception): ErrorMessageBuilder {
        return ErrorMessageBuilder.create(project, e, "Gradle import errors")
            .withDescription("Unable to build Kotlin project configuration")
    }

    override fun canBuild(modelName: String) =
        modelName == GradleKotlinBuildScriptsModel::class.java.name

    override fun buildAll(modelName: String, project: Project): GradleKotlinBuildScriptsModel {
        val start = System.currentTimeMillis()
        val result = GradleKotlinBuildScriptsModelImpl(visitProject(project))
        val duration = System.currentTimeMillis() - start
        println("Duration of visit project $modelName project=$project [$duration]")
        return result
    }

    private fun visitProject(project: Project): List<GradleKotlinBuildScriptModel> {
        val buildScripts = mutableListOf<GradleKotlinBuildScriptModel>()

        val fileNames = arrayOf("build.gradle.kts", "init.gradle.kts", "settings.gradle.kts")
        fileNames.map { File(project.projectDir, it) }.filter { it.exists() && it.isFile }.forEach { gradleKtsFile ->
            visitScript(project, gradleKtsFile, buildScripts)
        }
        return buildScripts
    }



    private fun visitScript(
        project: Project,
        buildGradleKtsFile: File,
        buildScripts: MutableList<GradleKotlinBuildScriptModel>
    ) {
        val model = getDependencyModel(buildGradleKtsFile, project)
        val modelClass = model.javaClass
        buildScripts.add(
            GradleKotlinBuildScriptModelImpl(
                buildGradleKtsFile.absolutePath,
                loadList<File>(modelClass, model, "getClassPath").map { it.absolutePath },
                loadList<File>(modelClass, model, "getSourcePath").map { it.absolutePath },
                loadList<String>(modelClass, model, "getImplicitImports")
            )
        )
    }

    var modelBuilder: Any? = null

    private fun getDependencyModel(buildGradleKtsFile: File, project: Project): Any {
        val classLoader = javaClass.classLoader
        if (modelBuilder == null) {
            val builderClass = classLoader.loadClass("org.gradle.kotlin.dsl.tooling.builders.KotlinBuildScriptModelBuilder")
            val builder = builderClass.kotlin.objectInstance

            val pClass = classLoader.loadClass("org.gradle.kotlin.dsl.tooling.builders.KotlinBuildScriptModelParameter")
            val pConstructor = pClass.kotlin.constructors
                .first()
                .also { it.isAccessible = true }
            val parameters =
                if (pConstructor.parameters.size == 1) pConstructor.call(buildGradleKtsFile.canonicalPath)
                else pConstructor.call(buildGradleKtsFile.canonicalPath, System.currentTimeMillis().toString())

            modelBuilder = builderClass.declaredMethods
                .find { it.name == "scriptModelBuilderFor" }!!
                .also { it.isAccessible = true }
                .invoke(builder, project, parameters)

        }

        val model = modelBuilder!!::class.declaredFunctions
            .find { it.name == "buildModel" }!!
            .also {
                if (!it.isAccessible) {
                    it.isAccessible = true
                }}
            .call(modelBuilder)!!
        return model
    }

    private fun <T> loadList(modelClass: Class<Any>, model: Any, getter: String): List<T> {
        return (modelClass.declaredMethods.find { it.name == getter }!!.also {
            if (!it.isAccessible) {
                it.isAccessible = true
            }
        }.invoke(model) as List<Any>).map {
            cast(it) as T
        }
    }

    fun cast(obj: Any): Any {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use {
            it.writeObject(obj)
        }

        ByteArrayInputStream(bytes.toByteArray()).also {
            return ObjectInputStream(it).readObject()
        }
    }
}