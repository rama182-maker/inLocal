package com.example.inlocal

import java.awt.*
import javax.swing.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox

class InLocalPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val envFiles = run {
        val resDir = java.io.File("${project.basePath}/src/main/resources")
        val isYaml = java.io.File(resDir, "application.yml").exists()
        if (isYaml) {
            resDir.listFiles { f -> f.name.matches(Regex("application-(.+)\\.yml")) }?.map { it.name } ?: emptyList()
        } else {
            resDir.listFiles { f -> f.name.matches(Regex("application-(.+)\\.properties")) }?.map { it.name } ?: emptyList()
        }
    }
    private val envDropdown = ComboBox(envFiles.toTypedArray())
    private val kafkaCheck = JCheckBox("Disable Kafka Consumers", true)
    private val redisCheck = JCheckBox("Disable Redis Listeners", true)
    private val urlCheck = JCheckBox("Replace with Internal URLs", true)
    private val applyButton = JButton("Apply Changes")
    private val restoreButton = JButton("Restore Configuration")

    init {
        val controlPanel = JPanel(GridLayout(0, 1, 5, 5))
        controlPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        val isYaml = java.io.File("${project.basePath}/src/main/resources/application.yml").exists()
        val infoLabel = JLabel(
                if (isYaml) "YML/YAML detected: changes will be applied to application.yml and the chosen application-env.yml"
                else "PROPERTIES detected: changes will be applied to application.properties and the chosen application-env.properties"
        )
        controlPanel.add(infoLabel)
        controlPanel.add(JLabel("Select Environment file to be modified for inLocal:"))
        controlPanel.add(envDropdown)
        controlPanel.add(urlCheck)
        controlPanel.add(kafkaCheck)
        controlPanel.add(redisCheck)
        val buttonPanel = JPanel()
        buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.X_AXIS)
        buttonPanel.alignmentX = Component.LEFT_ALIGNMENT
        buttonPanel.add(restoreButton)
        buttonPanel.add(Box.createRigidArea(Dimension(10, 0)))
        buttonPanel.add(applyButton)
        controlPanel.add(buttonPanel)

        add(controlPanel, BorderLayout.NORTH)

        applyButton.addActionListener {
            val selectedFile = envDropdown.selectedItem as String
            val env = selectedFile.substringAfter("application-").substringBeforeLast('.')
            InLocalService(project).applyEnv(env, kafkaCheck.isSelected, redisCheck.isSelected, urlCheck.isSelected)
        }

        restoreButton.addActionListener {
            val selectedFile = envDropdown.selectedItem as String
            val env = selectedFile.substringAfter("application-").substringBeforeLast('.')
            InLocalService(project).restore(env)
        }
    }
}