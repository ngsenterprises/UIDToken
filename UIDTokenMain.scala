

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import scala.collection.mutable.{HashMap, ListBuffer}
import scala.collection.immutable.Map

object UIDToken extends App {

  val parms: collection.immutable.Map[String, String] =
    args.foldLeft(new HashMap[String, String]) { (acc, arg) => {
      val k_v = arg.split("=")
      acc += (k_v(0) -> k_v(1))
    }}.toMap

  println("start App")
  val sys = ActorSystem("UIDToken")
  val supervisor = sys.actorOf(UIDProducer.props, name = "UIDProducer")

  supervisor ! StartSystemMsg
}
