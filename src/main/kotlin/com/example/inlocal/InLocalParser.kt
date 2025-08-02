package com.example.inlocal

class InLocalParser {
    fun parse(content: String): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        val regex = Regex("""^(\w+)\s*:\s*\{\s*$""")
        var currentEnv: String? = null

        for (line in content.lines()) {
            val trimmed = line.trim()
            when {
                regex.matches(trimmed) -> {
                    val envKey = regex.find(trimmed)!!.groupValues[1]
                    currentEnv = envKey
                    result[currentEnv] = mutableMapOf()
                }
                trimmed == "}" -> {
                    currentEnv = null
                }
                currentEnv != null && trimmed.contains("=") -> {
                    val (key, value) = trimmed.split("=", limit = 2).map { it.trim() }
                    result[currentEnv]?.put(key, value)
                }
            }
        }

        return result
    }
}