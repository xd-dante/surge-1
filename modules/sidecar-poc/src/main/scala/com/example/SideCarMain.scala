// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package com.example

import akka.actor.ActorSystem
import akka.grpc.GrpcClientSettings
import com.ukg.surge.sidecarpoc._
import com.ukg.surge.sidecarpoc.app._
import surge.core.{ SerializedAggregate, SerializedMessage, SurgeAggregateReadFormatting, SurgeAggregateWriteFormatting, SurgeEventWriteFormatting }
import surge.core.command.AggregateCommandModelCoreTrait
import surge.kafka.KafkaTopic
import surge.scaladsl.command.{ AggregateCommandModel, SurgeCommand, SurgeCommandBusinessLogic }
import surge.scaladsl.common.{ CommandFailure, CommandResult, CommandSuccess }

import java.util.UUID
import scala.concurrent.{ Await, Future }
import scala.util.{ Failure, Success, Try }
import scala.concurrent.duration._

object SideCarMain extends App {

  implicit val sys = ActorSystem("hello")
  implicit val ec = sys.dispatcher

  val clientSettings = GrpcClientSettings.connectToServiceAt("127.0.0.1", 8080).withTls(false)

  val businessLogicService: BusinessLogicService = BusinessLogicServiceClient(clientSettings)

  val genericCommandModel = new AggregateCommandModel[State, Command, Event] {

    override def processCommand(aggregate: Option[State], command: Command): Try[Seq[Event]] = {
      val reply = Await.result(businessLogicService.processCommand(ProcessCommandRequest(aggregate, Some(command))), atMost = 7.seconds)
      if (reply.rejection == null) {
        Success(reply.events)
      } else {
        Failure(new Exception(reply.rejection))
      }
    }

    override def handleEvent(aggregate: Option[State], event: Event): Option[State] = {
      val reply = Await.result(businessLogicService.handleEvent(HandleEventRequest(aggregate, Some(event))), atMost = 7.seconds)
      reply.state
    }
  }

  val genericSurgeModel = new SurgeCommandBusinessLogic[UUID, State, Command, Event] {

    override def commandModel: AggregateCommandModelCoreTrait[State, Command, Nothing, Event] = genericCommandModel

    override def eventsTopic: KafkaTopic = KafkaTopic("events")

    override def aggregateReadFormatting: SurgeAggregateReadFormatting[State] = new SurgeAggregateReadFormatting[State] {
      override def readState(bytes: Array[Byte]): Option[State] =
        Some(State.parseFrom(bytes))
    }

    override def eventWriteFormatting: SurgeEventWriteFormatting[Event] = new SurgeEventWriteFormatting[Event] {
      override def writeEvent(evt: Event): SerializedMessage = SerializedMessage(key = evt.aggregateId, value = Event.toByteArray(evt), headers = Map.empty)
    }

    override def aggregateWriteFormatting: SurgeAggregateWriteFormatting[State] = new SurgeAggregateWriteFormatting[State] {
      override def writeState(agg: State): SerializedAggregate = new SerializedAggregate(State.toByteArray(agg), Map.empty)
    }

    override def aggregateName: String = "photo"

    override def stateTopic: KafkaTopic = KafkaTopic("state")
  }

  lazy val surgeEngine: SurgeCommand[UUID, State, Command, Nothing, Event] = {
    val engine = SurgeCommand(sys, genericSurgeModel, sys.settings.config)
    engine.start()
    engine
  }

  val randomAgId = UUID.randomUUID()

  val future: Future[CommandResult[State]] =
    surgeEngine.aggregateFor(randomAgId).sendCommand(Command(aggregateId = randomAgId.toString, payload = TagPerson(personName = "John").toByteString))

  future.onComplete { case result: Try[CommandResult[State]] =>
    result match {
      case Failure(exception) =>
        println(exception.getMessage)
        exception.printStackTrace()
      case Success(value) =>
        value match {
          case CommandSuccess(aggregateState) =>
            aggregateState match {
              case Some(value) =>
                println("Got state:" + value)
              case None =>
                println("Empty state")
            }
          case CommandFailure(reason) =>
            println(reason)
        }
    }
  }

}