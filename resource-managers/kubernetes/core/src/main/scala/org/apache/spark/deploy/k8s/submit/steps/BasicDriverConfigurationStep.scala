/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.k8s.submit.steps

import scala.collection.JavaConverters._
import io.fabric8.kubernetes.api.model._
import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.deploy.k8s.Config._
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.deploy.k8s.KubernetesUtils
import org.apache.spark.deploy.k8s.submit.KubernetesDriverSpec
import org.apache.spark.internal.config.{DRIVER_CLASS_PATH, DRIVER_MEMORY, DRIVER_MEMORY_OVERHEAD}
import org.apache.spark.util.Utils

/**
 * Performs basic configuration for the driver pod.
 */
private[spark] class BasicDriverConfigurationStep(
    kubernetesAppId: String,
    resourceNamePrefix: String,
    driverLabels: Map[String, String],
    imagePullPolicy: String,
    appName: String,
    mainClass: String,
    appArgs: Array[String],
    sparkConf: SparkConf) extends DriverConfigurationStep {

  private val driverPodName = sparkConf
    .get(KUBERNETES_DRIVER_POD_NAME)
    .getOrElse(s"$resourceNamePrefix-driver")

  private val driverExtraClasspath = sparkConf.get(DRIVER_CLASS_PATH)

  private val driverContainerImage = sparkConf
    .get(DRIVER_CONTAINER_IMAGE)
    .getOrElse(throw new SparkException("Must specify the driver container image"))

  private val imagePullSecrets = sparkConf.get(IMAGE_PULL_SECRETS)

  // CPU settings
  private val driverCpuCores = sparkConf.getOption("spark.driver.cores").getOrElse("1")
  private val driverLimitCores = sparkConf.get(KUBERNETES_DRIVER_LIMIT_CORES)

  // Memory settings
  private val driverMemoryMiB = sparkConf.get(DRIVER_MEMORY)
  private val driverMemoryString = sparkConf.get(
    DRIVER_MEMORY.key, DRIVER_MEMORY.defaultValueString)
  private val memoryOverheadMiB = sparkConf
    .get(DRIVER_MEMORY_OVERHEAD)
    .getOrElse(math.max((MEMORY_OVERHEAD_FACTOR * driverMemoryMiB).toInt, MEMORY_OVERHEAD_MIN_MIB))
  private val driverMemoryWithOverheadMiB = driverMemoryMiB + memoryOverheadMiB

  override def configureDriver(driverSpec: KubernetesDriverSpec): KubernetesDriverSpec = {
    val driverExtraClasspathEnv = driverExtraClasspath.map { classPath =>
      new EnvVarBuilder()
        .withName(ENV_CLASSPATH)
        .withValue(classPath)
        .build()
    }

    val driverCustomAnnotations = KubernetesUtils.parsePrefixedKeyValuePairs(
      sparkConf, KUBERNETES_DRIVER_ANNOTATION_PREFIX)
    require(!driverCustomAnnotations.contains(SPARK_APP_NAME_ANNOTATION),
      s"Annotation with key $SPARK_APP_NAME_ANNOTATION is not allowed as it is reserved for" +
        " Spark bookkeeping operations.")

    val driverCustomEnvs = sparkConf.getAllWithPrefix(KUBERNETES_DRIVER_ENV_KEY).toSeq
      .map { env =>
        new EnvVarBuilder()
          .withName(env._1)
          .withValue(env._2)
          .build()
      }

    val clusterEnvs = sparkConf.getAllWithPrefix(KUBERNETES_CLUSTER_ENV_KEY).toSeq
      .map { case (name, value) =>
        new EnvVarBuilder()
          .withName(name)
          .withValue(value)
          .build()
      }

    val maprTicketSecret =
      s"$KUBERNETES_DRIVER_SECRETS_PREFIX${sparkConf.get(MAPR_TICKET_SECRET_PREFIX)}"

    val maprTicketEnv = sparkConf
      .getAllWithPrefix(maprTicketSecret).toSeq
      .map { case (_, value) =>
        new EnvVarBuilder()
          .withName(MAPR_TICKETFILE_LOCATION)
          .withValue(value + s"/${sparkConf.get(MAPR_TICKET_SECRET_KEY)}")
          .build()
      }

    val maprSslSecret =
      s"$KUBERNETES_DRIVER_SECRETS_PREFIX${sparkConf.get(MAPR_SSL_SECRET_PREFIX)}"

    val maprSslEnv = sparkConf
      .getAllWithPrefix(maprSslSecret).toSeq
      .map { case (_, value) =>
        new EnvVarBuilder()
          .withName(MAPR_SSL_LOCATION)
          .withValue(value)
          .build()
      }

    val driverAnnotations = driverCustomAnnotations ++ Map(SPARK_APP_NAME_ANNOTATION -> appName)

    val nodeSelector = KubernetesUtils.parsePrefixedKeyValuePairs(
      sparkConf, KUBERNETES_NODE_SELECTOR_PREFIX)

    val driverCpuQuantity = new QuantityBuilder(false)
      .withAmount(driverCpuCores)
      .build()
    val driverMemoryQuantity = new QuantityBuilder(false)
      .withAmount(s"${driverMemoryMiB}Mi")
      .build()
    val driverMemoryLimitQuantity = new QuantityBuilder(false)
      .withAmount(s"${driverMemoryWithOverheadMiB}Mi")
      .build()
    val maybeCpuLimitQuantity = driverLimitCores.map { limitCores =>
      ("cpu", new QuantityBuilder(false).withAmount(limitCores).build())
    }

    val clusterConfMap = sparkConf.get(MAPR_CLUSTER_CONFIGMAP).toString
    val clusterUserSecrets = sparkConf.get(MAPR_CLUSTER_USER_SECRETS).toString

    val username = sparkConf
      .get(CONTAINER_USER_NAME)
      .getOrElse(Utils.getCurrentUserName())
    val userId = sparkConf
      .get(CONTAINER_USER_ID)
      .getOrElse(Utils.getCurrentUserId())
    val userGroups = Utils.getCurrentUserGroups(sparkConf, username)
    val userGroupsIds = Utils.getCurrentUserGroupsIds(sparkConf, username)

    if (userId.isEmpty || userGroupsIds.isEmpty) {
      throw new RuntimeException(s"Error getting uid/gid for user=$username")
    }

    val driverContainer = new ContainerBuilder(driverSpec.driverContainer)
      .withName(DRIVER_CONTAINER_NAME)
      .withImage(driverContainerImage)
      .withImagePullPolicy(imagePullPolicy)
      .addAllToEnv(driverCustomEnvs.asJava)
      .addAllToEnv(clusterEnvs.asJava)
      .addAllToEnv(maprTicketEnv.asJava)
      .addAllToEnv(maprSslEnv.asJava)
      .addToEnv(driverExtraClasspathEnv.toSeq: _*)
      .addNewEnv()
        .withName(CURRENT_USER)
        .withValue(username)
        .endEnv()
      .addNewEnv()
        .withName(USER_GROUPS)
        .withValue(userGroups.mkString(" "))
        .endEnv()
      .addNewEnv()
        .withName(CURRENT_USER_ID)
        .withValue(userId)
        .endEnv()
      .addNewEnv()
        .withName(USER_GROUPS_IDS)
        .withValue(userGroupsIds.mkString(" "))
        .endEnv()
      .addNewEnv()
        .withName(ENV_DRIVER_MEMORY)
        .withValue(driverMemoryString)
        .endEnv()
      .addNewEnv()
        .withName(ENV_DRIVER_MAIN_CLASS)
        .withValue(mainClass)
        .endEnv()
      .addNewEnv()
        .withName(ENV_DRIVER_ARGS)
        .withValue(appArgs.mkString(" "))
        .endEnv()
      .addNewEnv()
        .withName(ENV_DRIVER_BIND_ADDRESS)
        .withValueFrom(new EnvVarSourceBuilder()
          .withNewFieldRef("v1", "status.podIP")
          .build())
        .endEnv()
        .addNewEnvFrom()
          .withNewConfigMapRef()
            .withName(clusterConfMap)
            .endConfigMapRef()
          .endEnvFrom()
      .addNewEnvFrom()
        .withNewSecretRef()
          .withName(clusterUserSecrets)
          .endSecretRef()
        .endEnvFrom()
      .withNewResources()
        .addToRequests("cpu", driverCpuQuantity)
        .addToRequests("memory", driverMemoryQuantity)
        .addToLimits("memory", driverMemoryLimitQuantity)
        .addToLimits(maybeCpuLimitQuantity.toMap.asJava)
        .endResources()
      .addToArgs("driver")
      .build()

    val parsedImagePullSecrets = KubernetesUtils.parseImagePullSecrets(imagePullSecrets)

    val baseDriverPod = new PodBuilder(driverSpec.driverPod)
      .editOrNewMetadata()
        .withName(driverPodName)
        .addToLabels(driverLabels.asJava)
        .addToAnnotations(driverAnnotations.asJava)
      .endMetadata()
      .withNewSpec()
        .withRestartPolicy("Never")
        .withNodeSelector(nodeSelector.asJava)
        .withImagePullSecrets(parsedImagePullSecrets.asJava)
        .endSpec()
      .build()

    val resolvedSparkConf = driverSpec.driverSparkConf.clone()
      .setIfMissing(KUBERNETES_DRIVER_POD_NAME, driverPodName)
      .set("spark.app.id", kubernetesAppId)
      .set(KUBERNETES_EXECUTOR_POD_NAME_PREFIX, resourceNamePrefix)

    driverSpec.copy(
      driverPod = baseDriverPod,
      driverSparkConf = resolvedSparkConf,
      driverContainer = driverContainer)
  }
}
