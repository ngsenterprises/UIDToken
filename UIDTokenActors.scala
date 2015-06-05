
import java.io.{FileWriter, File}
import javax.security.auth.login.Configuration

import akka.actor.SupervisorStrategy._
import akka.actor.{OneForOneStrategy, Actor, Props, ActorRef}

import akka.actor.Props
import akka.actor._
import akka.routing.{RoundRobinRoutingLogic, SmallestMailboxRoutingLogic, Router, ActorRefRoutee}
import akka.actor.ActorSystem._


import com.typesafe.config.ConfigFactory
import scala.collection.immutable.{Nil, List, Map}
import scala.collection.mutable
import scala.io.Source
import scala.util.control.NonFatal
import scala.collection.mutable._
import scala.annotation._
import scala.concurrent.duration._

import scala.concurrent.duration.Duration
import scala.concurrent.duration.TimeUnit
import akka.actor.Cancellable


case class UID(uid: Int)

sealed trait UIDMsg
case class Req_UIDTokenMsg(sender: ActorRef) extends UIDMsg
case class UIDTokenMsg(uid: UID) extends UIDMsg
case class Ret_UIDTokenMsg(uid: UID) extends UIDMsg

case object Req_UIDReminderMsg extends UIDMsg
case object Ret_UIDReminderMsg extends UIDMsg

case class StartSystemMsg(parms: collection.immutable.Map[String, String]) extends UIDMsg
case object StopSystemMsg extends UIDMsg
case object SystemCheckMsg extends UIDMsg

case object StopConsumerMsg extends UIDMsg




object UIDConsumer {
  def props = Props(classOf[UIDConsumer])
}//-----------------------

class UIDConsumer extends Actor {

  import context._

  val UIDQue = new ListBuffer[UID]

  var maxReq = ConfigFactory.load.getInt("app.command.maxUIDReq")
  var uidReqCount = 0

  var schReq: Option[Cancellable] = None
  var schRet: Option[Cancellable] = None

  def receive = {

    case StartSystemMsg => {
      schReq = Some(context.system.scheduler.schedule(
        Duration(0, SECONDS), Duration(500, MILLISECONDS),
        context.self, Req_UIDReminderMsg))
      schRet = Some(context.system.scheduler.schedule(
        Duration(2, SECONDS), Duration(1, SECONDS),
        context.self, Ret_UIDReminderMsg))
    }

    case UIDTokenMsg(uid: UID) => { UIDQue += uid }

    case Req_UIDReminderMsg => {
      if (uidReqCount < maxReq) {
        uidReqCount += 1
        context.parent ! new Req_UIDTokenMsg(context.self)
      } else schReq.map( _.cancel)
    }

    case Ret_UIDReminderMsg => {
      if (!UIDQue.isEmpty)
        context.parent ! new Ret_UIDTokenMsg(UIDQue.remove(0))
      else {
        schRet.map( _.cancel)
        context.parent ! StopConsumerMsg
      }
    }
  }
}//-------------------------------



object UIDProducer {
  def props = Props(classOf[UIDProducer])
}


class UIDProducer extends Actor {

  //println("UIDProducer creation")
  //var parms:Map[String, String] = Map.empty[String, String]
  val maxActors = ConfigFactory.load.getInt("app.akka.maxActors")
  val reqQue = new Queue[Req_UIDTokenMsg]
  val UIDBuffer = new Queue[UID]
  val maxUID = ConfigFactory.load.getInt("app.command.maxUID")
  (0 until maxUID).foreach( index => { UIDBuffer += new UID(index) })
  var activeConsumers = 0
  var startTime:Long = 0


  //println("UIDProducer creation 2")
  override def supervisorStrategy = {
    OneForOneStrategy() {
      case e: Exception if (NonFatal(e)) => {
        println("supervisorStrategy RESTART." + e.toString)
        Restart
      }
      case e => {
        println("supervisorStrategy ESCALATE." + e.toString)
        Escalate
      }
    }
  }

  //println("UIDProducer creation 3")
  val router = {
    println("routees maxActors " +maxActors)
    val consumers = Vector.fill(maxActors) {
      val r = context.actorOf(UIDConsumer.props)
      context.watch(r)
      ActorRefRoutee(r)
    }
    Router(RoundRobinRoutingLogic(), consumers)
  }
  //println("UIDProducer creation 4")





  def receive = {

    case StartSystemMsg => {
      println("StartSystemMsg")
      startTime = System.currentTimeMillis

      router.routees.foreach( ar => {
        activeConsumers += 1
        ar.send(StartSystemMsg, context.self)
      })
      if (activeConsumers <= 0)
        context.self ! StopSystemMsg
    }

    case StopSystemMsg => {
      println("StopSystemMsg time: " +(System.currentTimeMillis -startTime))
      System.exit(0)
    }

    case reqm: Req_UIDTokenMsg => {//(sdr:ActorRef) => {
      if (UIDBuffer.nonEmpty)
        reqm.sender ! new UIDTokenMsg(UIDBuffer.dequeue)
      else reqQue += reqm

    }
    case Ret_UIDTokenMsg(uid:UID) => {
      //println("Ret_UIDTokenMsg uid: " +uid)
      UIDBuffer += uid
      if (reqQue.nonEmpty) {
        val reqm = reqQue.dequeue
        reqm.sender ! new UIDTokenMsg(UIDBuffer.dequeue)
      }
    }

    case StopConsumerMsg => {
      sender ! Kill
      activeConsumers -= 1
      if (activeConsumers <= 0)
        context.self ! StopSystemMsg
    }
  }//..............................................
}
