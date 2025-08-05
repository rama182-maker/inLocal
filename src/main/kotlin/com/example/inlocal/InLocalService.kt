package com.example.inlocal

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Properties

class InLocalService(private val project: Project) {

    private val options = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
    }

    fun applyEnv(env: String, disableKafka: Boolean, disableRedis: Boolean, replaceUrls: Boolean) {
        val basePath = project.basePath ?: return

        val backupDir = File("$basePath/.env-backups")
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }

        val isYaml = File("$basePath/src/main/resources/application.yml").exists()
        val appFile = if (isYaml) {
            File("$basePath/src/main/resources/application.yml")
        } else {
            File("$basePath/src/main/resources/application.properties")
        }
        val targetFile = if (isYaml) {
            File("$basePath/src/main/resources/application-$env.yml")
        } else {
            File("$basePath/src/main/resources/application-$env.properties")
        }

        val appBackup = File(backupDir, appFile.name + ".bak")
        val targetBackup = File(backupDir, targetFile.name + ".bak")

        if (!appBackup.exists()) {
            appBackup.writeText(appFile.readText())
        }

        if (!targetBackup.exists()) {
            targetBackup.writeText(targetFile.readText())
        }

        val envFile = File("$basePath/src/main/resources/.env")

        if (isYaml) {
            val yaml = Yaml(options)

            val main = yaml.load<MutableMap<String, Any>>(appFile.readText())
            val profileMap = (main["spring"] as? MutableMap<String, Any>) ?: mutableMapOf()
            profileMap["profiles"] = mapOf("active" to env)
            main["spring"] = profileMap
            appFile.bufferedWriter().use { it.write(yaml.dump(main)) }

            val envMap = yaml.load<MutableMap<String, Any>>(targetFile.readText())

            if (disableKafka) updateMapPath(envMap, "kafka.listener.enabled", false)
            if (disableRedis) updateMapPath(envMap, "redis.consumer.enabled", false)


            val parsedEnv = InLocalParser().parse(envFile.readText())["${env.uppercase()}"]
            parsedEnv?.forEach { (key, value) ->
                replaceEnvPlaceholders(envMap, key, value)
            }

            if (replaceUrls) {
                val urlMappings = InLocalParser().parse(envFile.readText())["URLS_${env.uppercase()}"]
                if (urlMappings != null) {
                    replaceUrlValues(envMap, urlMappings)
                }
            }

            targetFile.bufferedWriter().use { it.write(yaml.dump(envMap)) }
        } else {
            val props = Properties()
            props.load(appFile.reader())
            props.setProperty("spring.profiles.active", env)
            appFile.writer().use { props.store(it, null) }


            val targetProps = Properties()
            targetProps.load(targetFile.reader())
            if (disableKafka) targetProps.setProperty("kafka.listener.enabled", "false")
            if (disableRedis) targetProps.setProperty("redis.consumer.enabled", "false")


            val parsedEnv = InLocalParser().parse(envFile.readText())["${env.uppercase()}"]
            parsedEnv?.forEach { (key, value) ->
                targetProps.replaceAll { k, v ->
                    if (v is String && v.contains("\${$key}")) v.replace("\${$key}", value) else v
                }
            }

            val parsedEnvFile = InLocalParser().parse(envFile.readText())
            val urlMappings = parsedEnvFile["URLS_${env.uppercase()}"]
            if (replaceUrls && urlMappings != null) {
                val updated = Properties()
                for ((k, v) in targetProps) {
                    val newVal = urlMappings.entries.fold(v.toString()) { acc, (old, new) ->
                        acc.replace(old, new)
                    }
                    updated[k.toString()] = newVal
                }
                targetProps.clear()
                targetProps.putAll(updated)
            }

            targetFile.writer().use { targetProps.store(it, null) }
        }

        val vfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        vfs.refreshAndFindFileByIoFile(appFile)?.refresh(false, false)
        vfs.refreshAndFindFileByIoFile(targetFile)?.refresh(false, false)
        Messages.showInfoMessage("Applied '$env' configuration successfully.", "inLocal")
    }

    fun restore(env: String) {
        val basePath = project.basePath ?: return
        val backupDir = File("$basePath/.env-backups")

        val isYaml = File("$basePath/src/main/resources/application.yml").exists()
        val appFile = if (isYaml) {
            File("$basePath/src/main/resources/application.yml")
        } else {
            File("$basePath/src/main/resources/application.properties")
        }
        val targetFile = if (isYaml) {
            File("$basePath/src/main/resources/application-$env.yml")
        } else {
            File("$basePath/src/main/resources/application-$env.properties")
        }

        val appBackup = File(backupDir, appFile.name + ".bak")
        val targetBackup = File(backupDir, targetFile.name + ".bak")

        if (appBackup.exists()) {
            appBackup.bufferedReader().use { reader ->
                appFile.bufferedWriter().use { writer -> writer.write(reader.readText()) }
            }
        }

        if (targetBackup.exists()) {
            targetBackup.bufferedReader().use { reader ->
                targetFile.bufferedWriter().use { writer -> writer.write(reader.readText()) }
            }
        }
        val vfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        vfs.refreshAndFindFileByIoFile(appFile)?.refresh(false, false)
        vfs.refreshAndFindFileByIoFile(targetFile)?.refresh(false, false)
        Messages.showInfoMessage("Restored original configuration for '$env'.", "inLocal")
    }

    private fun updateMapPath(map: MutableMap<String, Any>, path: String, value: Any) {
        val keys = path.split(".")
        var current: MutableMap<String, Any> = map
        for (i in 0 until keys.size - 1) {
            current = (current[keys[i]] as? MutableMap<String, Any>) ?: mutableMapOf<String, Any>().also {
                current[keys[i]] = it
            }
        }
        current[keys.last()] = value
    }

    private fun replaceEnvPlaceholders(map: MutableMap<String, Any>, key: String, value: String) {
        for ((k, v) in map) {
            when (v) {
                is String -> {
                    if (v.contains("\${$key}")) {
                        map[k] = v.replace("\${$key}", value)
                    }
                }
                is MutableMap<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    replaceEnvPlaceholders(v as MutableMap<String, Any>, key, value)
                }
            }
        }
    }

    private fun replaceUrlValues(map: MutableMap<String, Any>, urlMap: Map<String, String>) {
        for ((k, v) in map) {
            when (v) {
                is String -> {
                    val newVal = urlMap.entries.fold(v) { acc, (old, new) ->
                        acc.replace(old, new)
                    }
                    map[k] = newVal
                }
                is MutableMap<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    replaceUrlValues(v as MutableMap<String, Any>, urlMap)
                }
            }
        }
    }
}