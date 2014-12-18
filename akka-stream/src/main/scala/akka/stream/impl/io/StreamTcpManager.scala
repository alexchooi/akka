/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.net.InetSocketAddress
import java.net.URLEncoder
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import akka.actor.Actor
import akka.io.Inet.SocketOption
import akka.io.Tcp
import akka.stream.MaterializerSettings
import akka.stream.impl.ActorProcessor
import akka.stream.impl.ActorPublisher
import akka.stream.scaladsl.StreamTcp
import akka.util.ByteString
import org.reactivestreams.Processor
import org.reactivestreams.Subscriber

/**
 * INTERNAL API
 */
private[akka] object StreamTcpManager {
  /**
   * INTERNAL API
   */
  private[akka] case class Connect(processorPromise: Promise[Processor[ByteString, ByteString]],
                                   localAddressPromise: Promise[InetSocketAddress],
                                   remoteAddress: InetSocketAddress,
                                   localAddress: Option[InetSocketAddress],
                                   options: immutable.Traversable[SocketOption],
                                   connectTimeout: Duration,
                                   idleTimeout: Duration)

  /**
   * INTERNAL API
   */
  private[akka] case class Bind(localAddressPromise: Promise[InetSocketAddress],
                                unbindPromise: Promise[() ⇒ Future[Unit]],
                                flowSubscriber: Subscriber[StreamTcp.IncomingConnection],
                                endpoint: InetSocketAddress,
                                backlog: Int,
                                options: immutable.Traversable[SocketOption],
                                idleTimeout: Duration)

  /**
   * INTERNAL API
   */
  private[akka] case class ExposedProcessor(processor: Processor[ByteString, ByteString])

}

/**
 * INTERNAL API
 */
private[akka] class StreamTcpManager extends Actor {
  import StreamTcpManager._

  var nameCounter = 0
  def encName(prefix: String, endpoint: InetSocketAddress) = {
    nameCounter += 1
    s"$prefix-$nameCounter-${URLEncoder.encode(endpoint.toString, "utf-8")}"
  }

  def receive: Receive = {
    case Connect(processorPromise, localAddressPromise, remoteAddress, localAddress, options, connectTimeout, _) ⇒
      val connTimeout = connectTimeout match {
        case x: FiniteDuration ⇒ Some(x)
        case _                 ⇒ None
      }
      val processorActor = context.actorOf(TcpStreamActor.outboundProps(processorPromise, localAddressPromise,
        Tcp.Connect(remoteAddress, localAddress, options, connTimeout, pullMode = true),
        materializerSettings = MaterializerSettings(context.system)), name = encName("client", remoteAddress))
      processorActor ! ExposedProcessor(ActorProcessor[ByteString, ByteString](processorActor))

    case Bind(localAddressPromise, unbindPromise, flowSubscriber, endpoint, backlog, options, _) ⇒
      val publisherActor = context.actorOf(TcpListenStreamActor.props(localAddressPromise, unbindPromise,
        flowSubscriber, Tcp.Bind(context.system.deadLetters, endpoint, backlog, options, pullMode = true),
        MaterializerSettings(context.system)), name = encName("server", endpoint))
      // this sends the ExposedPublisher message to the publisher actor automatically
      ActorPublisher[Any](publisherActor)
  }
}
