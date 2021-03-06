package com.geeksville.mavlink

import com.geeksville.akka.InstrumentedActor
import org.mavlink.messages.MAVLinkMessage
import org.mavlink.messages.ardupilotmega._
import LogIncomingMavlink._
import scala.concurrent._
import scala.concurrent.duration._
import com.geeksville.akka.EventStream
import org.mavlink.messages.MAV_TYPE
import org.mavlink.messages.MAV_MODE_FLAG
import akka.actor.Cancellable

case class MsgHeartbeatLost(id: Int)
case class MsgHeartbeatFound(id: Int)
case class MsgArmChanged(armed: Boolean)
case class MsgSystemStatusChanged(stat: Option[Int])

/**
 * Watches for arrival of a heartbeat, if we don't see one we print an error message
 */
trait HeartbeatMonitor extends InstrumentedActor {
  import context._

  private case object WatchdogExpired

  val eventStream = new EventStream

  private var timer: Option[Cancellable] = None
  private var mySysId: Option[Int] = None

  var customMode: Option[Int] = None
  var baseMode: Option[Int] = None
  var systemStatus: Option[Int] = None

  /// A MAV_TYPE vehicle code
  var vehicleType: Option[Int] = None

  // A MAV_AUTOPILOT autopilot mfg code
  var autopilotType: Option[Int] = None

  /// Has the vehicle been armed (ever) during this session
  var hasBeenArmed = false

  def isArmed: Boolean = baseMode.map(isArmed).getOrElse(false)

  /// Parse a baseMode to see if we are armed
  private def isArmed(m: Int): Boolean = (m & MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED) != 0

  def hasHeartbeat = mySysId.isDefined

  def heartbeatSysId = mySysId

  protected def publishEvent(a: Any) { eventStream.publish(a) }

  def onReceive = {
    case msg: msg_heartbeat =>
      // We don't care about the heartbeats from a GCS
      val typ = msg.`type`
      if (typ != MAV_TYPE.MAV_TYPE_GCS) {
        val oldVal = customMode
        val oldBase = baseMode
        val newVal = msg.custom_mode.toInt
        val oldArmed = isArmed
        val oldStatus = systemStatus
        customMode = Some(newVal)
        baseMode = Some(msg.base_mode)
        autopilotType = Some(msg.autopilot)
        systemStatus = Some(msg.system_status)

        val oldVehicle = vehicleType
        vehicleType = Some(typ)

        // This will call onHeartbeatChanged
        resetWatchdog(msg.sysId)

        if (oldVal != customMode || oldVehicle != vehicleType || baseMode != oldBase)
          onModeChanged(oldVal, newVal)
        if (oldArmed != isArmed)
          onArmedChanged(isArmed)
        if (systemStatus != oldStatus)
          onSystemStatusChanged(systemStatus)
      }

    //case msg: MAVLinkMessage => log.warn(s"Unknown mavlink msg: ${msg.messageType} $msg")

    case WatchdogExpired =>
      forceLostHeartbeat()
  }

  /**
   * Declare that we don't have heartbeat anymore (possibly due to some non timer based knowledge)
   */
  protected def forceLostHeartbeat() {
    cancelWatchdog()
    mySysId.foreach { id =>
      publishEvent(MsgHeartbeatLost(id))
      mySysId = None
      systemStatus = None
      onHeartbeatLost()
      onSystemStatusChanged(systemStatus)
    }
  }

  override def postStop() {
    cancelWatchdog()
    super.postStop()
  }

  protected def onArmedChanged(armed: Boolean) {
    log.info("Armed changed: " + armed)
    if (armed)
      hasBeenArmed = true

    publishEvent(MsgArmChanged(armed))
  }

  protected def onModeChanged(old: Option[Int], m: Int) {
    log.info(s"Mode change, $old -> $m")
  }

  protected def onSystemStatusChanged(m: Option[Int]) {
    log.error("Received new status: " + m)
    publishEvent(MsgSystemStatusChanged(m))
  }

  protected def onHeartbeatLost() {
    log.error("Lost heartbeat")
  }

  protected def onHeartbeatFound() {
    mySysId.foreach { id => log.info("Contact established with sysId " + id) }
  }

  private def resetWatchdog(sysId: Int) {
    if (!mySysId.isDefined) {
      mySysId = Some(sysId)
      onHeartbeatFound()
      publishEvent(MsgHeartbeatFound(sysId))
    }
    cancelWatchdog()

    timer = Some(context.system.scheduler.scheduleOnce(30 seconds, self, WatchdogExpired))
  }

  private def cancelWatchdog() {
    timer.foreach(_.cancel())
    timer = None
  }
}

