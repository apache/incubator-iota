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

package org.apache.iota.fey

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, PoisonPill, Props, Terminated}
import akka.routing.{ActorRefRoutee, DefaultResizer, GetRoutees, SmallestMailboxPool}
import org.apache.iota.fey.JSON_PATH._
import play.api.libs.json.JsObject

import scala.collection.mutable.HashMap
import scala.concurrent.duration._

protected class Ensemble(val orchestrationID: String,
                         val orchestrationName: String,
                         val ensembleSpec: JsObject) extends Actor with ActorLogging {

  import Ensemble._

  val monitoring_actor = FEY_MONITOR.actorRef
  var performers_metadata: Map[String, Performer] = Map.empty[String, Performer]
  var connectors: Map[String, Array[String]] = Map.empty[String, Array[String]]
  var performer: Map[String, ActorRef] = Map.empty[String, ActorRef]

  override def receive: Receive = {

    case STOP_PERFORMERS => stopPerformers()

    case PRINT_ENSEMBLE =>
      val ed = connectors.map(connector => {
        s""" \t ${connector._1} : ${connector._2.mkString("[", ",", "]")}"""
      }).mkString("\n")
      val nd = performers_metadata.map(performer_metadata => s"${performer_metadata._1}").mkString("[", ",", "]")
      val actors = performer.map(actor => actor._2.path.name).mkString("[", " | ", "]")
      log.info(s"Edges: \n$ed \nNodes: \n\t$nd \nPerformers \n\t$actors")
      context.actorSelection(s"*") ! FeyGenericActor.PRINT_PATH

    case Terminated(actor) =>
      monitoring_actor ! Monitor.TERMINATE(actor.path.toString, Utils.getTimestamp)
      log.error(s"DEAD nPerformers ${actor.path.name}")
      context.children.foreach { child =>
        context.unwatch(child)
        context.stop(child)
      }
      throw new RestartEnsemble(s"DEAD Performer ${actor.path.name}")

    case GetRoutees => //Discard

    case x => log.warning(s"Message $x not treated by Ensemble")
  }

  /**
    * If any of the performer dies, it tries to restart it.
    * If we could not be restarted, then the terminated message will be received
    * and Ensemble is going to throw an Exception to its orchestration
    * asking it to Restart the entire Ensemble. The restart will then stop all of its
    * children when call the preStart.
    */
  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 minute) {
      case e: Exception => Restart
    }

  /**
    * Uses the json spec to create the performers
    */
  override def preStart(): Unit = {

    monitoring_actor ! Monitor.START(Utils.getTimestamp)

    val connectors_js = (ensembleSpec \ CONNECTIONS).as[List[JsObject]]
    val performers_js = (ensembleSpec \ PERFORMERS).as[List[JsObject]]

    performers_metadata = extractPerformers(performers_js)
    connectors = extractConnections(connectors_js)

    performer = createPerformers()

    val ed = connectors.map(connector => {
      s""" \t ${connector._1} : ${connector._2.mkString("[", ",", "]")}"""
    }).mkString("\n")
    val nd = performers_metadata.map(performer => s"${performer._1}").mkString("[", ",", "]")
    val actors = performer.map(actor => actor._2.path.name).mkString("[", " | ", "]")
    log.info(s"Edges: \n$ed \nNodes: \n\t$nd \nPerformers \n\t$actors")

  }

  override def postStop(): Unit = {
    monitoring_actor ! Monitor.STOP(Utils.getTimestamp)
  }

  override def postRestart(reason: Throwable): Unit = {
    monitoring_actor ! Monitor.RESTART(reason, Utils.getTimestamp)
    preStart()
  }


  def getActorsName(): Set[String] = {
    performer.map(performer => performer._2.path.name).toSet
  }

  private def createPerformers(): Map[String, ActorRef] = {
    val tmpActors: HashMap[String, ActorRef] = HashMap.empty
    connectors.foreach {
      case (performerID: String, connectionIDs: Array[String]) =>
        try {
          createFeyActor(performerID, connectionIDs, tmpActors)
        } catch {
          /* if the creation fails, it will stop all the actors in the Ensemble */
          case e: Exception =>
            log.error(e, "During Performer creation")
            throw new RestartEnsemble("Not able to create the Performers.")
        }
    }

    //Treat performers that are not part of the connectors
    noConnectionsPerformers(tmpActors)

    tmpActors.toMap
  }

  /**
    * Creates actors that was not part of an Connection
    *
    * @param tmpActors
    */
  private def noConnectionsPerformers(tmpActors: HashMap[String, ActorRef]) = {
    performers_metadata.filter(performer_metadata => !tmpActors.contains(performer_metadata._1)).
      foreach(performer_metadata => {
        try {
          createFeyActor(performer_metadata._1, Array.empty, tmpActors)
        } catch {
          /* if the creation fails, it will stop all the actors in the Ensemble */
          case e: Exception =>
            log.error(e, "Problems while creating Performer")
            throw new RestartEnsemble("Not able to create the Performer.")
        }
      })
  }

  /**
    * Create an actorOf for the performer in the Ensemble
    *
    * @param performerID   performer uid
    * @param connectionIDs performer connections (connectors)
    * @param tmpActors     auxiliar map of actorsRef
    * @return (performerID, ActorRef of the performer)
    */
  private def createFeyActor(performerID: String, connectionIDs: Array[String], tmpActors: HashMap[String, ActorRef]): (String, ActorRef) = {
    if (!tmpActors.contains(performerID)) {
      val performerInfo = performers_metadata.getOrElse(performerID, CONSTANTS.NULL)
      if (Option(performerInfo).isDefined) {
        val connections: Map[String, ActorRef] = connectionIDs.map(connID => {
          createFeyActor(connID, connectors.getOrElse(connID, Array.empty), tmpActors)
        }).toMap

        val actorProps = getPerformer(performerInfo, connections)
        val actor: ActorRef = if (performerInfo.autoScale > 0) {

          val resizer = DefaultResizer(lowerBound = 1, upperBound = performerInfo.autoScale,
            messagesPerResize = CONFIG.MESSAGES_PER_RESIZE, backoffThreshold = 0.4)
          val smallestMailBox = SmallestMailboxPool(1, Some(resizer))

          context.actorOf(smallestMailBox.props(actorProps), name = performerID)

        } else {
          context.actorOf(actorProps, name = performerID)
        }

        context.watch(actor)
        tmpActors.put(performerID, actor)
        (performerID, actor)
      } else {
        throw new IllegalPerformerCreation(s"Performer $performerID is not defined in the JSON")
      }
    } else {
      (performerID, tmpActors.get(performerID).get)
    }
  }

  /**
    * Creates actor props based on JSON configuration
    *
    * @param performerInfo Performer object
    * @param connections   connections
    * @return Props of actor based on JSON config
    */
  private def getPerformer(performerInfo: Performer, connections: Map[String, ActorRef]): Props = {

    val clazz = loadClazzFromJar(performerInfo.classPath, s"${performerInfo.jarLocation}/${performerInfo.jarName}", performerInfo.jarName)

    val autoScale = if (performerInfo.autoScale > 0) true else false

    val actorProps = Props(clazz,
      performerInfo.parameters, performerInfo.backoff, connections, performerInfo.schedule, orchestrationName, orchestrationID, autoScale)

    if (performerInfo.controlAware) {
      actorProps.withDispatcher(CONFIG.CONTROL_AWARE_MAILBOX)
    } else {
      actorProps
    }

  }

  /**
    * Load a clazz instance of FeyGenericActor from a jar
    *
    * @param classPath   class path
    * @param jarLocation Full path where to load the jar from
    * @return clazz instance of FeyGenericActor
    */
  private def loadClazzFromJar(classPath: String, jarLocation: String, jarName: String): Class[FeyGenericActor] = {
    try {
      Utils.loadActorClassFromJar(jarLocation, classPath, jarName)
    } catch {
      case e: Exception =>
        log.error(e, s"Could not load class $classPath from jar $jarLocation. Please, check the Jar repository path as well the jar name")
        throw e
    }
  }

  override val toString = {
    val ed = connectors.map(connector => {
      s""" \t ${connector._1} : ${connector._2.mkString("[", ",", "]")}"""
    }).mkString("\n")
    val nd = performers_metadata.map(performer => s"${performer._1}").mkString("[", ",", "]")
    val actors = performer.map(actor => actor._2.path.name).mkString("[", " | ", "]")
    s"Edges: \n$ed \nNodes: \n\t$nd \nPerformer \n\t$actors"
  }

  def stopPerformers(): Unit = {
    performer.foreach(actor => actor._2 ! PoisonPill)
  }

}

object Ensemble {

  /**
    * Extract a map from the connectors json
    *
    * @param connectors connectors json
    * @return map of connectors
    */
  def extractConnections(connectors: List[JsObject]): Map[String, Array[String]] = {
    connectors.map(connector => {
      connector.keys.map(key => {
        (key, (connector \ key).as[List[String]].toArray)
      }).toMap
    }).flatten.toMap
  }

  /**
    * Extratc a map from the performers json
    *
    * @param performers performers json
    * @return Map of performers
    */
  def extractPerformers(performers: List[JsObject]): Map[String, Performer] = {
    performers.map(performer => {
      val id: String = (performer \ GUID).as[String]
      val schedule: Int = (performer \ SCHEDULE).as[Int]
      val backoff: Int = (performer \ BACKOFF).as[Int]
      val autoScale: Int = if (performer.keys.contains(PERFORMER_AUTO_SCALE)) (performer \ PERFORMER_AUTO_SCALE).as[Int] else 0
      val jarName: String = (performer \ SOURCE \ SOURCE_NAME).as[String]
      val classPath: String = (performer \ SOURCE \ SOURCE_CLASSPATH).as[String]
      val params: Map[String, String] = getMapOfParams((performer \ SOURCE \ SOURCE_PARAMS).as[JsObject])
      val controlAware: Boolean = if (performer.keys.contains(CONTROL_AWARE)) (performer \ CONTROL_AWARE).as[Boolean] else false
      val location: String = if ((performer \ SOURCE).as[JsObject].keys.contains(JAR_LOCATION)) CONFIG.DYNAMIC_JAR_REPO else CONFIG.JAR_REPOSITORY
      (id, new Performer(id, jarName, classPath, params, schedule.millisecond, backoff.millisecond, autoScale, controlAware, location))
    }).toMap
  }

  /**
    * returns a map of the params
    *
    * @param params params json
    * @return map of params where key is the json key and value is the json value for the key
    */
  private def getMapOfParams(params: JsObject): Map[String, String] = {
    params.keys.map(key => {
      (key.toString, (params \ key).as[String])
    }).toMap
  }

  /**
    * Message that send the START message to all of the Performers
    */
  case object STOP_PERFORMERS

  case object PRINT_ENSEMBLE

}

/**
  * Holds the performer information
  *
  * @param uid        performer uid
  * @param jarName    performer jar name
  * @param classPath  performer class path
  * @param parameters performer params
  * @param schedule   performer schedule interval
  * @param backoff    performer backoff interval
  */
case class Performer(uid: String, jarName: String,
                     classPath: String, parameters: Map[String, String],
                     schedule: FiniteDuration, backoff: FiniteDuration,
                     autoScale: Int, controlAware: Boolean, jarLocation: String)
