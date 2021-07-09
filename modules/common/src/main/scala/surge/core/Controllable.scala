// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package surge.core

import scala.concurrent.Future

trait Ack {
  def success: Boolean
  def error: Option[Throwable]
}

trait Controllable {
  def start(): Future[Ack]
  def restart(): Future[Ack]
  def stop(): Future[Ack]
  def shutdown(): Future[Ack]
}

class ControllableAdapter extends Controllable {
  final case class ControlAck(success: Boolean, error: Option[Throwable] = None) extends Ack

  override def start(): Future[Ack] = Future.successful[Ack](ControlAck(success = true))

  override def restart(): Future[Ack] = Future.successful[Ack](ControlAck(success = true))

  override def stop(): Future[Ack] = Future.successful[Ack](ControlAck(success = true))

  override def shutdown(): Future[ControlAck] = Future.successful(ControlAck(success = true))
}
