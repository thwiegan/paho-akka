package com.sandinh.paho.akka

import java.net.{URLDecoder, URLEncoder}
import akka.actor.{FSM, Props, Terminated}
import org.eclipse.paho.client.mqttv3._
import MqttException.REASON_CODE_CLIENT_NOT_CONNECTED
import scala.collection.mutable
import scala.util.control.NonFatal
import MqttPubSub._

object MqttPubSub {
  private[akka] val logger = org.log4s.getLogger

  //++++ ultilities ++++//
  @inline private def urlEnc(s: String) = URLEncoder.encode(s, "utf-8")
  @inline private def urlDec(s: String) = URLDecoder.decode(s, "utf-8")
}

/** Notes:
  * 1. MqttClientPersistence will be set to null. @see org.eclipse.paho.client.mqttv3.MqttMessage#setQos(int)
  * 2. MQTT client will auto-reconnect
  */
class MqttPubSub(cfg: PSConfig) extends FSM[PSState, Unit] {
  //setup MqttAsyncClient without MqttClientPersistence
  private[this] val client = {
    val c = new MqttAsyncClient(cfg.brokerUrl, MqttAsyncClient.generateClientId(), null)
    c.setCallback(new PubSubMqttCallback(self))
    c
  }
  //setup Connnection IMqttActionListener
  private[this] val conListener = new ConnListener(self)

  private[this] val subsListener = new SubscribeListener(self)

  /** Use to stash the Publish messages when disconnected.
    * `Long` in Elem type is System.nanoTime when receive the Publish message.
    * We need republish to the underlying mqtt client when go to connected.
    */
  private[this] val pubStash = mutable.Queue.empty[(Long, Publish)]

  /** Use to stash the Subscribe messages when disconnected.
    * We need resubscribe all subscriptions to the underlying mqtt client when go to connected
    * , so we store in a separated stash (instead of merging pubStash & subStash).
    */
  private[this] val subStash = mutable.Queue.empty[Subscribe]

  /** contains all successful `Subscribe`.
    * + add when the underlying client notify that the corresponding topic is successful subscribed.
    * + remove when the Subscribe.ref actor is terminated.
    * + use to re-subscribe after reconnected.
    */
  private[this] val subscribed = mutable.Set.empty[Subscribe]

  /** contains all subscribing `Subscribe`.
    * + always add when calling the underlying client.subscribe.
    * + always remove when the call is failed and/or remove later when the underlying client notify that the
    * subscription has either Success or Failed.
    */
  private[this] val subscribing = mutable.Set.empty[Subscribe]

  //reconnect attempt count, reset when connect success
  private[this] var connectCount = 0

  //++++ FSM logic ++++
  startWith(DisconnectedState, Unit)

  when(DisconnectedState) {
    case Event(Connect, _) =>
      logger.info(s"connecting to ${cfg.brokerUrl}..")
      //only receive Connect when client.isConnected == false so its safe here to call client.connect
      try {
        client.connect(cfg.conOpt, null, conListener)
      } catch {
        case NonFatal(e) =>
          logger.error(e)(s"can't connect to $cfg")
          delayConnect()
      }
      connectCount += 1
      stay()

    case Event(Connected, _) =>
      connectCount = 0
      subscribed foreach doSubscribe
      while (subStash.nonEmpty) self ! subStash.dequeue()
      //remove expired Publish messages
      if (cfg.stashTimeToLive.isFinite())
        pubStash.dequeueAll(_._1 + cfg.stashTimeToLive.toNanos < System.nanoTime)
      while (pubStash.nonEmpty) self ! pubStash.dequeue()._2
      goto(ConnectedState)

    case Event(x: Publish, _) =>
      if (pubStash.length > cfg.stashCapacity)
        while (pubStash.length > cfg.stashCapacity / 2)
          pubStash.dequeue()
      pubStash += (System.nanoTime -> x)
      stay()

    case Event(x: Subscribe, _) =>
      subStash += x
      stay()
  }

  when(ConnectedState) {
    case Event(p: Publish, _) =>
      try {
        client.publish(p.topic, p.message())
      } catch {
        //underlying client can be disconnected when this FSM is in state SConnected. See ResubscribeSpec
        case e: MqttException if e.getReasonCode == REASON_CODE_CLIENT_NOT_CONNECTED =>
          self ! Disconnected //delayConnect & goto SDisconnected
          self ! p //stash p
        case NonFatal(e) =>
          logger.error(e)(s"can't publish to ${p.topic}")
      }
      stay()

    case Event(sub: Subscribe, _) =>
      context.child(urlEnc(sub.topic)) match {
        case Some(t) => t ! sub //Topic t will (only) store & watch sub.ref
        case None    => doSubscribe(sub)
      }
      stay()

    //don't need handle Terminated(topicRef) in state SDisconnected
    case Event(Terminated(topicRef), _) =>
      try {
        client.unsubscribe(urlDec(topicRef.path.name))
      } catch {
        case e: MqttException if e.getReasonCode == REASON_CODE_CLIENT_NOT_CONNECTED => //do nothing
        case NonFatal(e) => logger.error(e)(s"can't unsubscribe from ${topicRef.path.name}")
      }
      stay()
  }

  whenUnhandled {
    case Event(msg: Message, _) =>
      val topicArr = msg.topic.split("/")

      context.children.filter { c =>
        val nameArr = urlDec(c.path.name).split("/")

        nameArr.last match {
          case "#" =>
            topicArr.zip(nameArr.dropRight(1)).forall {
              case (t, n) => t == n || n == "+"
            }

          case _ =>

            topicArr.length == nameArr.length &&
              topicArr.zip(nameArr).forall {
                case (t, n) => t == n || n == "+"
              }
        }
      }.foreach(_ ! msg)

      stay()

    case Event(UnderlyingSubsAck(topic, fail), _) =>
      val topicSubs = subscribing.filter(_.topic == topic)
      fail match {
        case None =>
          val encTopic = urlEnc(topic)
          val topicActor = context.child(encTopic) match {
            case None =>
              val t = context.actorOf(Props[Topic], name = encTopic)
              //TODO consider if we should use `handleChildTerminated` by override `SupervisorStrategy` instead of watch?
              context watch t
            case Some(t) => t
          }
          topicSubs.foreach { sub =>
            subscribed += sub
            topicActor ! sub
          }
        case Some(e) => //do nothing
      }
      topicSubs.foreach { sub =>
        sub.ref ! SubscribeAck(sub, fail)
        subscribing -= sub
      }
      stay()

    case Event(SubscriberTerminated(ref), _) =>
      subscribed.retain(_.ref != ref)
      stay()

    case Event(Disconnected, _) =>
      delayConnect()
      goto(DisconnectedState)
  }

  private def doSubscribe(sub: Subscribe): Unit = {
    //Given:
    //  val subs = subscribing.filter(_.topic == sub.topic)
    //We need call `client.subscribe` when:
    //  subs.isEmpty => subscribe first time
    //  subs.forall(_.qos < sub.qos) => resubscribe with higher qos
    if (subscribing.forall(x => x.topic != sub.topic || x.qos < sub.qos))
      try {
        client.subscribe(sub.topic, sub.qos, null, subsListener)
      } catch {
        case e: MqttException if e.getReasonCode == REASON_CODE_CLIENT_NOT_CONNECTED =>
          self ! Disconnected //delayConnect & goto SDisconnected
          self ! sub //stash Subscribe
        case NonFatal(e) =>
          self ! UnderlyingSubsAck(sub.topic, Some(e))
          logger.error(e)(s"subscribe call fail ${sub.topic}")
      }
    subscribing += sub
  }

  private def delayConnect(): Unit = {
    val delay = cfg.connectDelay(connectCount)
    logger.info(s"delay $delay before reconnect")
    setTimer("reconnect", Connect, delay)
  }

  initialize()

  self ! Connect
}
