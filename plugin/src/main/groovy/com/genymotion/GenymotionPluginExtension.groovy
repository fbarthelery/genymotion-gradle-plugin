/*
 * Copyright (C) 2015 Genymobile
 *
 * This file is part of GenymotionGradlePlugin.
 *
 * GenymotionGradlePlugin is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version
 *
 * GenymotionGradlePlugin is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GenymotionGradlePlugin.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.genymotion

import com.genymotion.model.CloudVDLaunchDsl
import com.genymotion.model.DeviceLocation
import com.genymotion.model.GenymotionConfig
import com.genymotion.model.VDLaunchDsl
import com.genymotion.tasks.GenymotionFinishTask
import com.genymotion.tasks.GenymotionLaunchTask
import com.genymotion.tools.AndroidPluginTools
import com.genymotion.tools.GMTool
import com.genymotion.tools.GMToolException
import com.genymotion.tools.Log
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.tasks.TaskProvider

/**
 * Holds all the properties defined in a `genymotion` entry defined in a Gradle file
 */
class GenymotionPluginExtension {

    private static String LAUNCH_MANUALLY_MESSAGE = "genymotionLaunch/Finish tasks are not injected " +
            "and have to be launched manually."

    final Project project
    private final NamedDomainObjectContainer<VDLaunchDsl> deviceLaunches
    private final NamedDomainObjectContainer<CloudVDLaunchDsl> cloudDeviceLaunches

    def genymotionConfig = new GenymotionConfig()
    public GenymotionConfig currentConfiguration = null

    GenymotionPluginExtension(Project project, deviceLaunches, cloudDeviceLaunches) {

        this.project = project
        this.deviceLaunches = deviceLaunches
        this.cloudDeviceLaunches = cloudDeviceLaunches
    }

    def devices(Closure closure) {
        deviceLaunches.configure(closure)
    }

    def getDevices(String flavor = null) {
        if (flavor == null) {
            return deviceLaunches.toList()
        }

        def devices = []
        deviceLaunches.each {
            if (it.hasFlavor(flavor)) {
                devices.add(it)
            }
        }
        return devices
    }

    def cloudDevices(Closure closure) {
        cloudDeviceLaunches.configure(closure)
    }

    def getCloudDevices(String flavor = null) {
        if (flavor == null) {
            return cloudDeviceLaunches.toList()
        }

        def devices = []
        cloudDeviceLaunches.each {
            if (it.hasFlavor(flavor)) {
                devices.add(it)
            }
        }
        return devices
    }

    def getDevicesByLocationAndFlavor(DeviceLocation deviceLocation, String flavor) {
        return deviceLocation == DeviceLocation.LOCAL ? getDevices(flavor) : getCloudDevices(flavor)
    }

    def checkParams() {

        //Check if the flavors entered exist
        checkProductFlavors()

        GMTool gmtool = GMTool.newInstance()
        deviceLaunches.each {
            it.checkParams(gmtool, project.genymotion.config.abortOnError)
        }
        gmtool.deviceLocation = DeviceLocation.CLOUD
        cloudDeviceLaunches.each {
            it.checkParams(gmtool, project.genymotion.config.abortOnError)
        }

        //check gmtool path is found
        gmtool.usage()
    }

    public void checkProductFlavors() {
        if (!AndroidPluginTools.hasAndroidPlugin(project)) {
            return
        }

        def androidFlavors = project.android.productFlavors*.name

        checkDeviceLaunchFlavors(deviceLaunches, androidFlavors)
        checkDeviceLaunchFlavors(cloudDeviceLaunches, androidFlavors)
    }

    private void checkDeviceLaunchFlavors(def launches, def androidFlavors) {
        launches.each {
            for (String flavor in it.productFlavors) {

                if (flavor == null) {
                    if (project.genymotion.config.abortOnError) {
                        throw new GMToolException("You entered a null product flavor on device $it.name. Please remove it to be able to continue the job")
                    } else {
                        Log.warn("You entered a null product flavor on device $it.name. It will be ignored.")
                    }

                } else if (!androidFlavors.contains(flavor)) {
                    if (project.genymotion.config.abortOnError) {
                        throw new GMToolException("Product flavor $flavor on device $it.name does not exist.")
                    } else {
                        Log.warn("Product flavor $flavor does not exist. It will be ignored.")
                    }
                }
            }

        }
    }
    /**
     * Task management
     */

    void injectTasks() {
        def taskLaunch = project.genymotion.config.taskLaunch

        if (!project.genymotion.config.automaticLaunch) {
            Log.info("Genymotion automatic launch disabled. " +
                    "Set automaticLaunch to true if you want to start genymotion devices automatically.")
            return
        }
        if (!taskLaunch) {
            Log.info("No task defined to launch Genymotion devices. " +
                    "Set a correct taskLaunch if you want to start genymotion devices automatically.")
            return
        }

        try {
            if (taskLaunch instanceof ArrayList) {
                taskLaunch.each {
                    injectTasksInto(it)
                }

            } else if (taskLaunch == AndroidPluginTools.DEFAULT_ANDROID_TASK_1_0) {
                //if we detect the android plugin or the default android test task
                if (AndroidPluginTools.hasAndroidPlugin(project) || project.tasks.findByName(AndroidPluginTools.DEFAULT_ANDROID_TASK_1_0) != null) {
                    injectAndroidTasks()
                } else {
                    Log.info("$AndroidPluginTools.DEFAULT_ANDROID_TASK_1_0 not found, " + LAUNCH_MANUALLY_MESSAGE)
                    return
                }

            } else if (taskLaunch instanceof String) {
                injectTasksInto(taskLaunch)

            } else {
                Log.warn("No destination task found, " + LAUNCH_MANUALLY_MESSAGE)
                return
            }

        } catch (UnknownTaskException e) {
            Log.error("Task $taskLaunch not found. " + LAUNCH_MANUALLY_MESSAGE)
        }
    }

    private void injectAndroidTasks() {
        def latestFinishTask = null
        project.android.testVariants.all { variant ->
            String flavorName = variant.productFlavors[0]?.name
            TaskProvider<Task> connectedTask = variant.connectedInstrumentTestProvider
            injectTasksInto(connectedTask, flavorName)
            if (latestFinishTask != null) {
                project.tasks.named(getLaunchTaskName(connectedTask.name)) {
                    it.mustRunAfter(latestFinishTask)
                }
            }
            latestFinishTask = getFinishTaskName(connectedTask.name)
        }
    }

    public void injectTasksInto(String taskName, String flavor = null) throws UnknownTaskException {
        def theTask = project.tasks.named(taskName)
        injectTasksInto(theTask, flavor)
    }

    public void injectTasksInto(TaskProvider<Task> theTask, String flavor = null) throws UnknownTaskException {
        if (project.genymotion.config.verbose) {
            Log.info("Adding genymotion dependency to " + theTask.name)
        }

        String launchName = getLaunchTaskName(theTask.name)
        String finishName = getFinishTaskName(theTask.name)

        TaskProvider<GenymotionFinishTask> finishTask = project.tasks.register(finishName, GenymotionFinishTask) {
            it.flavor = flavor
            it.mustRunAfter(theTask)
        }

        TaskProvider<Task> launchTask = project.tasks.register(launchName, GenymotionLaunchTask) {
            it.flavor = flavor
            // bug cause a circular dependency
            //  it.finalizedBy(finishTask)
        }

        theTask.configure {
            it.dependsOn(launchTask)
        }

        // due to previous bug we need to create this task
        launchTask.get().finalizedBy(finishTask)
    }

    public static String getFinishTaskName(String taskName) {
        GenymotionGradlePlugin.TASK_FINISH + taskName.capitalize()
    }

    public static String getLaunchTaskName(String taskName) {
        GenymotionGradlePlugin.TASK_LAUNCH + taskName.capitalize()
    }

    /**
     * Configuration management
     */
    def processConfiguration(GMTool gmtool = GMTool.newInstance()) {
        GenymotionConfig config = project.genymotion.config
        config.applyConfigFromFile(project)
        project.genymotion.config.version = gmtool.getVersion()
        if (!config.isEmpty()) {
            gmtool.setConfig(config)
            if (config.license) {
                gmtool.setLicense(config.license)
            }
        }
    }
}
