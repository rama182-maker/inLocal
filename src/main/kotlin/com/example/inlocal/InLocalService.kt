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

            val documents = yaml.loadAll(appFile.readText()).toList().map {
                @Suppress("UNCHECKED_CAST")
                it as MutableMap<String, Any>
            }.toMutableList()

            for (doc in documents) {
                val spring = doc["spring"] as? MutableMap<String, Any>
                if (spring != null) {
                    spring["profiles"] = mapOf("active" to env)
                    break
                }
            }

            appFile.bufferedWriter().use { writer ->
                documents.forEach { writer.write(yaml.dump(it)); writer.write("---\n") }
            }

            val targetDocuments = yaml.loadAll(targetFile.readText()).toList().map {
                @Suppress("UNCHECKED_CAST")
                it as MutableMap<String, Any>
            }.toMutableList()

            var kafkaModified = false
            var redisModified = false

            for (targetDoc in targetDocuments) {
                if (disableKafka && hasNestedKey(targetDoc, listOf("kafka", "listener", "enabled"))) {
                    updateMapPath(targetDoc, "kafka.listener.enabled", false)
                    kafkaModified = true
                }

                if (disableRedis && hasNestedKey(targetDoc, listOf("redis", "consumer", "enabled"))) {
                    updateMapPath(targetDoc, "redis.consumer.enabled", false)
                    redisModified = true
                }

                val parsedEnv = InLocalParser().parse(envFile.readText())["${env.uppercase()}"]
                parsedEnv?.forEach { (key, value) ->
                    replaceEnvPlaceholders(targetDoc, key, value)
                }

                if (replaceUrls) {
                    val urlMappings = InLocalParser().parse(envFile.readText())["URLS_${env.uppercase()}"]
                    if (urlMappings != null) {
                        replaceUrlValues(targetDoc, urlMappings)
                    }
                }
            }

            // If the key wasn't found in any doc, apply once to the first doc
            if (disableKafka && !kafkaModified && targetDocuments.isNotEmpty()) {
                updateMapPath(targetDocuments.first(), "kafka.listener.enabled", false)
            }

            if (disableRedis && !redisModified && targetDocuments.isNotEmpty()) {
                updateMapPath(targetDocuments.first(), "redis.consumer.enabled", false)
            }

            targetFile.bufferedWriter().use { writer ->
                targetDocuments.forEach { writer.write(yaml.dump(it)); writer.write("---\n") }
            }
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
                targetProps.replaceAll { _, v ->
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

    private fun hasNestedKey(map: Map<String, Any>, keys: List<String>): Boolean {
        var current: Any? = map
        for (key in keys) {
            if (current is Map<*, *>) {
                current = current[key]
            } else {
                return false
            }
        }
        return true
    }
}
